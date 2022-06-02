/*
 * Copyright (C) 2008-2011, Google Inc.
 * Copyright (C) 2007, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2006-2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.dfs;

import static org.eclipse.jgit.internal.storage.dfs.DfsObjDatabase.PackSource.UNREACHABLE_GARBAGE;
import static org.eclipse.jgit.internal.storage.pack.PackExt.BITMAP_INDEX;
import static org.eclipse.jgit.internal.storage.pack.PackExt.INDEX;
import static org.eclipse.jgit.internal.storage.pack.PackExt.PACK;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.text.MessageFormat;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.LargeObjectException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.PackInvalidException;
import org.eclipse.jgit.errors.StoredObjectRepresentationNotAvailableException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.internal.storage.file.PackBitmapIndex;
import org.eclipse.jgit.internal.storage.file.PackIndex;
import org.eclipse.jgit.internal.storage.file.PackReverseIndex;
import org.eclipse.jgit.internal.storage.pack.BinaryDelta;
import org.eclipse.jgit.internal.storage.pack.PackOutputStream;
import org.eclipse.jgit.internal.storage.pack.StoredObjectRepresentation;
import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.util.LongList;

public final class DfsPackFile extends BlockBasedFile {
	private static final int REC_SIZE = Constants.OBJECT_ID_LENGTH + 8;
	private static final long REF_POSITION = 0;

	private volatile PackIndex index;
	private volatile PackReverseIndex reverseIndex;
	private volatile PackBitmapIndex bitmapIndex;
	private volatile LongList corruptObjects;

	private final Object corruptObjectsLock = new Object();

	DfsPackFile(DfsBlockCache cache, DfsPackDescription desc) {
		super(cache, desc, PACK);

		int bs = desc.getBlockSize(PACK);
		if(bs > 0) {
			setBlockSize(bs);
		}

		long sz = desc.getFileSize(PACK);
		length = sz > 0 ? sz : -1;
	}

	public DfsPackDescription getPackDescription() {
		return desc;
	}

	void setPackIndex(PackIndex idx) {
		long objCnt = idx.getObjectCount();
		int recSize = Constants.OBJECT_ID_LENGTH + 8;
		long sz = objCnt * recSize;
		cache.putRef(desc.getStreamKey(INDEX), sz, idx);
		index = idx;
	}

	public PackIndex getPackIndex(DfsReader ctx) throws IOException {
		return idx(ctx);
	}

	private PackIndex idx(DfsReader ctx) throws IOException {
		if(index != null) {
			return index;
		}

		if(invalid) {
			throw new PackInvalidException(getFileName(), invalidatingCause);
		}

		Repository.getGlobalListenerList()
				.dispatch(new BeforeDfsPackIndexLoadedEvent());
		try {
			DfsStreamKey idxKey = desc.getStreamKey(INDEX);
			AtomicBoolean cacheHit = new AtomicBoolean(true);
			DfsBlockCache.Ref<PackIndex> idxref = cache.getOrLoadRef(idxKey,
					REF_POSITION, () -> {
						cacheHit.set(false);
						return loadPackIndex(ctx, idxKey);
					});
			cacheHit.get();
			PackIndex idx = idxref.get();
			if(index == null && idx != null) {
				index = idx;
			}
			return index;
		} catch(IOException e) {
			invalid = true;
			invalidatingCause = e;
			throw e;
		}
	}

	boolean isGarbage() {
		return desc.getPackSource() == UNREACHABLE_GARBAGE;
	}

	public PackBitmapIndex getBitmapIndex(DfsReader ctx) throws IOException {
		if(invalid || isGarbage() || !desc.hasFileExt(BITMAP_INDEX)) {
			return null;
		}

		if(bitmapIndex != null) {
			return bitmapIndex;
		}

		DfsStreamKey bitmapKey = desc.getStreamKey(BITMAP_INDEX);
		AtomicBoolean cacheHit = new AtomicBoolean(true);
		DfsBlockCache.Ref<PackBitmapIndex> idxref = cache
				.getOrLoadRef(bitmapKey, REF_POSITION, () -> {
					cacheHit.set(false);
					return loadBitmapIndex(ctx, bitmapKey);
				});
		cacheHit.get();
		PackBitmapIndex bmidx = idxref.get();
		if(bitmapIndex == null && bmidx != null) {
			bitmapIndex = bmidx;
		}
		return bitmapIndex;
	}

	PackReverseIndex getReverseIdx(DfsReader ctx) throws IOException {
		if(reverseIndex != null) {
			return reverseIndex;
		}

		PackIndex idx = idx(ctx);
		DfsStreamKey revKey = new DfsStreamKey.ForReverseIndex(
				desc.getStreamKey(INDEX));
		AtomicBoolean cacheHit = new AtomicBoolean(true);
		DfsBlockCache.Ref<PackReverseIndex> revref = cache.getOrLoadRef(revKey,
				REF_POSITION, () -> {
					cacheHit.set(false);
					return loadReverseIdx(revKey, idx);
				});
		cacheHit.get();
		PackReverseIndex revidx = revref.get();
		if(reverseIndex == null && revidx != null) {
			reverseIndex = revidx;
		}
		return reverseIndex;
	}

	public boolean hasObject(DfsReader ctx, AnyObjectId id) throws IOException {
		final long offset = idx(ctx).findOffset(id);
		return 0 < offset && !isCorrupt(offset);
	}

	ObjectLoader get(DfsReader ctx, AnyObjectId id)
			throws IOException {
		long offset = idx(ctx).findOffset(id);
		return 0 < offset && !isCorrupt(offset) ? load(ctx, offset) : null;
	}

	long findOffset(DfsReader ctx, AnyObjectId id) throws IOException {
		return idx(ctx).findOffset(id);
	}

	void resolve(DfsReader ctx, Set<ObjectId> matches, AbbreviatedObjectId id,
				 int matchLimit) throws IOException {
		idx(ctx).resolve(matches, id, matchLimit);
	}

	private byte[] decompress(long position, int sz, DfsReader ctx)
			throws IOException, DataFormatException {
		byte[] dstbuf;
		try {
			dstbuf = new byte[sz];
		} catch(OutOfMemoryError noMemory) {
			return null;
		}

		if(ctx.inflate(this, position, dstbuf, false) != sz) {
			throw new EOFException(MessageFormat.format(JGitText.get().shortCompressedStreamAt, position));
		}
		return dstbuf;
	}

	void copyPackAsIs(PackOutputStream out, DfsReader ctx) throws IOException {
		if(length == -1) {
			ctx.pin(this, 0);
			ctx.unpin();
		}
		try(ReadableChannel rc = ctx.db.openFile(desc, PACK)) {
			int sz = ctx.getOptions().getStreamPackBufferSize();
			if(sz > 0) {
				rc.setReadAheadBytes(sz);
			}
			if(cache.shouldCopyThroughCache(length)) {
				copyPackThroughCache(out, rc);
			} else {
				copyPackBypassCache(out, rc);
			}
		}
	}

	private void copyPackThroughCache(PackOutputStream out, ReadableChannel rc) throws IOException {
		long position = 12;
		long remaining = length - (12 + 20);
		while(0 < remaining) {
			DfsBlock b = cache.getOrLoad(this, position, () -> rc);
			int ptr = (int) (position - b.start);
			if(b.size() <= ptr) {
				throw packfileIsTruncated();
			}
			int n = (int) Math.min(b.size() - ptr, remaining);
			b.write(out, position, n);
			position += n;
			remaining -= n;
		}
	}

	private long copyPackBypassCache(PackOutputStream out, ReadableChannel rc) throws IOException {
		ByteBuffer buf = newCopyBuffer(out, rc);
		long position = 12;
		long remaining = length - (12 + 20);
		boolean packHeadSkipped = false;
		while(0 < remaining) {
			DfsBlock b = cache.get(key, alignToBlock(position));
			if(b != null) {
				int ptr = (int) (position - b.start);
				if(b.size() <= ptr) {
					throw packfileIsTruncated();
				}
				int n = (int) Math.min(b.size() - ptr, remaining);
				b.write(out, position, n);
				position += n;
				remaining -= n;
				rc.position(position);
				packHeadSkipped = true;
				continue;
			}

			int ptr = packHeadSkipped ? 0 : 12;
			buf.position(0);
			int bufLen = read(rc, buf);
			if(bufLen <= ptr) {
				throw packfileIsTruncated();
			}
			int n = (int) Math.min(bufLen - ptr, remaining);
			out.write(buf.array(), ptr, n);
			position += n;
			remaining -= n;
			packHeadSkipped = true;
		}
		return position;
	}

	private ByteBuffer newCopyBuffer(PackOutputStream out, ReadableChannel rc) {
		int bs = blockSize(rc);
		byte[] copyBuf = out.getCopyBuffer();
		if(bs > copyBuf.length) {
			copyBuf = new byte[bs];
		}
		return ByteBuffer.wrap(copyBuf, 0, bs);
	}

	void copyAsIs(PackOutputStream out, DfsObjectToPack src,
				  boolean validate, DfsReader ctx) throws IOException,
			StoredObjectRepresentationNotAvailableException {
		final CRC32 crc1 = validate ? new CRC32() : null;
		final CRC32 crc2 = validate ? new CRC32() : null;
		final byte[] buf = out.getCopyBuffer();

		try {
			readFully(src.offset, buf, 0, 20, ctx);
		} catch(IOException ioError) {
			throw new StoredObjectRepresentationNotAvailableException(ioError);
		}
		int c = buf[0] & 0xff;
		final int typeCode = (c >> 4) & 7;
		long inflatedLength = c & 15;
		int shift = 4;
		int headerCnt = 1;
		while((c & 0x80) != 0) {
			c = buf[headerCnt++] & 0xff;
			inflatedLength += ((long) (c & 0x7f)) << shift;
			shift += 7;
		}

		if(typeCode == Constants.OBJ_OFS_DELTA) {
			do {
				c = buf[headerCnt++] & 0xff;
			} while((c & 128) != 0);
			if(validate) {
				crc1.update(buf, 0, headerCnt);
				crc2.update(buf, 0, headerCnt);
			}
		} else if(typeCode == Constants.OBJ_REF_DELTA) {
			if(validate) {
				crc1.update(buf, 0, headerCnt);
				crc2.update(buf, 0, headerCnt);
			}

			readFully(src.offset + headerCnt, buf, 0, 20, ctx);
			if(validate) {
				crc1.update(buf, 0, 20);
				crc2.update(buf, 0, 20);
			}
			headerCnt += 20;
		} else if(validate) {
			crc1.update(buf, 0, headerCnt);
			crc2.update(buf, 0, headerCnt);
		}

		final long dataOffset = src.offset + headerCnt;
		final long dataLength = src.length;
		final long expectedCRC;
		final DfsBlock quickCopy;

		try {
			quickCopy = ctx.quickCopy(this, dataOffset, dataLength);

			if(validate && idx(ctx).hasCRC32Support()) {
				expectedCRC = idx(ctx).findCRC32(src);
				if(quickCopy != null) {
					quickCopy.crc32(crc1, dataOffset, (int) dataLength);
				} else {
					long pos = dataOffset;
					long cnt = dataLength;
					while(cnt > 0) {
						final int n = (int) Math.min(cnt, buf.length);
						readFully(pos, buf, 0, n, ctx);
						crc1.update(buf, 0, n);
						pos += n;
						cnt -= n;
					}
				}
				if(crc1.getValue() != expectedCRC) {
					setCorrupt(src.offset);
					throw new CorruptObjectException(MessageFormat.format(
							JGitText.get().objectAtHasBadZlibStream, src.offset, getFileName()));
				}
			} else if(validate) {
				Inflater inf = ctx.inflater();
				byte[] tmp = new byte[1024];
				if(quickCopy != null) {
					quickCopy.check(inf, dataOffset, (int) dataLength);
				} else {
					long pos = dataOffset;
					long cnt = dataLength;
					while(cnt > 0) {
						final int n = (int) Math.min(cnt, buf.length);
						readFully(pos, buf, 0, n, ctx);
						crc1.update(buf, 0, n);
						inf.setInput(buf, 0, n);
						pos += n;
						cnt -= n;
					}
				}
				if(!inf.finished() || inf.getBytesRead() != dataLength) {
					setCorrupt(src.offset);
					throw new EOFException(MessageFormat.format(JGitText.get().shortCompressedStreamAt, src.offset));
				}
				expectedCRC = crc1.getValue();
			} else {
				expectedCRC = -1;
			}
		} catch(DataFormatException dataFormat) {
			setCorrupt(src.offset);

			CorruptObjectException corruptObject = new CorruptObjectException(
					MessageFormat.format(JGitText.get().objectAtHasBadZlibStream, src.offset, getFileName()),
					dataFormat);

			throw new StoredObjectRepresentationNotAvailableException(
					corruptObject);

		} catch(IOException ioError) {
			throw new StoredObjectRepresentationNotAvailableException(ioError);
		}

		if(quickCopy != null) {
			out.writeHeader(src, inflatedLength);
			quickCopy.write(out, dataOffset, (int) dataLength);

		} else if(dataLength <= buf.length) {
			if(!validate) {
				long pos = dataOffset;
				long cnt = dataLength;
				while(cnt > 0) {
					final int n = (int) Math.min(cnt, buf.length);
					readFully(pos, buf, 0, n, ctx);
					pos += n;
					cnt -= n;
				}
			}
			out.writeHeader(src, inflatedLength);
			out.write(buf, 0, (int) dataLength);
		} else {
			out.writeHeader(src, inflatedLength);
			long pos = dataOffset;
			long cnt = dataLength;
			while(cnt > 0) {
				final int n = (int) Math.min(cnt, buf.length);
				readFully(pos, buf, 0, n, ctx);
				if(validate) {
					crc2.update(buf, 0, n);
				}
				out.write(buf, 0, n);
				pos += n;
				cnt -= n;
			}
			if(validate) {
				if(crc2.getValue() != expectedCRC) {
					throw new CorruptObjectException(MessageFormat.format(JGitText.get().objectAtHasBadZlibStream,
							src.offset, getFileName()));
				}
			}
		}
	}

	private IOException packfileIsTruncated() {
		invalid = true;
		IOException exc = new IOException(MessageFormat.format(
				JGitText.get().packfileIsTruncated, getFileName()));
		invalidatingCause = exc;
		return exc;
	}

	private void readFully(long position, byte[] dstbuf, int dstoff, int cnt,
						   DfsReader ctx) throws IOException {
		while(cnt > 0) {
			int copied = ctx.copy(this, position, dstbuf, dstoff, cnt);
			if(copied == 0) {
				throw new EOFException();
			}
			position += copied;
			dstoff += copied;
			cnt -= copied;
		}
	}

	ObjectLoader load(DfsReader ctx, long pos)
			throws IOException {
		try {
			final byte[] ib = ctx.tempId;
			Delta delta = null;
			byte[] data = null;
			int type = Constants.OBJ_BAD;
			boolean cached = false;

			SEARCH:
			for(; ; ) {
				readFully(pos, ib, 0, 20, ctx);
				int c = ib[0] & 0xff;
				final int typeCode = (c >> 4) & 7;
				long sz = c & 15;
				int shift = 4;
				int p = 1;
				while((c & 0x80) != 0) {
					c = ib[p++] & 0xff;
					sz += ((long) (c & 0x7f)) << shift;
					shift += 7;
				}

				switch(typeCode) {
					case Constants.OBJ_COMMIT:
					case Constants.OBJ_TREE:
					case Constants.OBJ_BLOB:
					case Constants.OBJ_TAG: {
						if(delta != null) {
							data = decompress(pos + p, (int) sz, ctx);
							type = typeCode;
							break SEARCH;
						}

						if(sz < ctx.getStreamFileThreshold()) {
							data = decompress(pos + p, (int) sz, ctx);
							if(data != null) {
								return new ObjectLoader.SmallObject(typeCode, data);
							}
						}
						return new LargePackedWholeObject(typeCode, sz, pos, p, this, ctx.db);
					}

					case Constants.OBJ_OFS_DELTA: {
						c = ib[p++] & 0xff;
						long base = c & 127;
						while((c & 128) != 0) {
							base += 1;
							c = ib[p++] & 0xff;
							base <<= 7;
							base += (c & 127);
						}
						base = pos - base;
						delta = new Delta(delta, pos, (int) sz, p, base);
						if(sz != delta.deltaSize) {
							break SEARCH;
						}

						DeltaBaseCache.Entry e = ctx.getDeltaBaseCache().get(key, base);
						if(e != null) {
							type = e.type;
							data = e.data;
							cached = true;
							break SEARCH;
						}
						pos = base;
						continue;
					}

					case Constants.OBJ_REF_DELTA: {
						readFully(pos + p, ib, 0, 20, ctx);
						long base = findDeltaBase(ctx, ObjectId.fromRaw(ib));
						delta = new Delta(delta, pos, (int) sz, p + 20, base);
						if(sz != delta.deltaSize) {
							break SEARCH;
						}

						DeltaBaseCache.Entry e = ctx.getDeltaBaseCache().get(key, base);
						if(e != null) {
							type = e.type;
							data = e.data;
							cached = true;
							break SEARCH;
						}
						pos = base;
						continue;
					}

					default:
						throw new IOException(MessageFormat.format(JGitText.get().unknownObjectType, typeCode));
				}
			}

			if(data == null)
				throw new LargeObjectException();

			do {
				if(cached) {
					cached = false;
				} else if(delta.next == null) {
					ctx.getDeltaBaseCache().put(key, delta.basePos, type, data);
				}

				pos = delta.deltaPos;

				byte[] cmds = decompress(pos + delta.hdrLen, delta.deltaSize, ctx);
				if(cmds == null) {
					throw new LargeObjectException();
				}

				final long sz = BinaryDelta.getResultSize(cmds);
				if(Integer.MAX_VALUE <= sz) {
					throw new LargeObjectException.ExceedsByteArrayLimit();
				}

				final byte[] result;
				try {
					result = new byte[(int) sz];
				} catch(OutOfMemoryError tooBig) {
					throw new LargeObjectException.OutOfMemory(tooBig);
				}

				BinaryDelta.apply(data, cmds, result);
				data = result;
				delta = delta.next;
			} while(delta != null);

			return new ObjectLoader.SmallObject(type, data);

		} catch(DataFormatException dfe) {
			throw new CorruptObjectException(
					MessageFormat.format(JGitText.get().objectAtHasBadZlibStream, pos, getFileName()),
					dfe);
		}
	}

	private long findDeltaBase(DfsReader ctx, ObjectId baseId) throws IOException {
		long ofs = idx(ctx).findOffset(baseId);
		if(ofs < 0) {
			throw new MissingObjectException(baseId,
					JGitText.get().missingDeltaBase);
		}
		return ofs;
	}

	private static class Delta {

		final Delta next;
		final long deltaPos;
		final int deltaSize;
		final int hdrLen;
		final long basePos;

		Delta(Delta next, long ofs, int sz, int hdrLen, long baseOffset) {
			this.next = next;
			this.deltaPos = ofs;
			this.deltaSize = sz;
			this.hdrLen = hdrLen;
			this.basePos = baseOffset;
		}
	}

	byte[] getDeltaHeader(DfsReader wc, long pos) throws IOException, DataFormatException {
		final byte[] hdr = new byte[32];
		wc.inflate(this, pos, hdr, true);
		return hdr;
	}

	long getObjectSize(DfsReader ctx, AnyObjectId id) throws IOException {
		final long offset = idx(ctx).findOffset(id);
		return 0 < offset ? getObjectSize(ctx, offset) : -1;
	}

	long getObjectSize(DfsReader ctx, long pos)
			throws IOException {
		final byte[] ib = ctx.tempId;
		readFully(pos, ib, 0, 20, ctx);
		int c = ib[0] & 0xff;
		final int type = (c >> 4) & 7;
		long sz = c & 15;
		int shift = 4;
		int p = 1;
		while((c & 0x80) != 0) {
			c = ib[p++] & 0xff;
			sz += ((long) (c & 0x7f)) << shift;
			shift += 7;
		}

		long deltaAt;
		switch(type) {
			case Constants.OBJ_COMMIT:
			case Constants.OBJ_TREE:
			case Constants.OBJ_BLOB:
			case Constants.OBJ_TAG:
				return sz;

			case Constants.OBJ_OFS_DELTA:
				c = ib[p++] & 0xff;
				while((c & 128) != 0) {
					c = ib[p++] & 0xff;
				}
				deltaAt = pos + p;
				break;

			case Constants.OBJ_REF_DELTA:
				deltaAt = pos + p + 20;
				break;

			default:
				throw new IOException(MessageFormat.format(JGitText.get().unknownObjectType, type));
		}

		try {
			return BinaryDelta.getResultSize(getDeltaHeader(ctx, deltaAt));
		} catch(DataFormatException dfe) {
			throw new CorruptObjectException(
					MessageFormat.format(JGitText.get().objectAtHasBadZlibStream, pos, getFileName()), dfe);
		}
	}

	void representation(DfsObjectRepresentation r, final long pos, DfsReader ctx, PackReverseIndex rev)
			throws IOException {
		r.offset = pos;
		final byte[] ib = ctx.tempId;
		readFully(pos, ib, 0, 20, ctx);
		int c = ib[0] & 0xff;
		int p = 1;
		final int typeCode = (c >> 4) & 7;
		while((c & 0x80) != 0) {
			c = ib[p++] & 0xff;
		}

		long len = rev.findNextOffset(pos, length - 20) - pos;
		switch(typeCode) {
			case Constants.OBJ_COMMIT:
			case Constants.OBJ_TREE:
			case Constants.OBJ_BLOB:
			case Constants.OBJ_TAG:
				r.format = StoredObjectRepresentation.PACK_WHOLE;
				r.baseId = null;
				r.length = len - p;
				return;

			case Constants.OBJ_OFS_DELTA: {
				c = ib[p++] & 0xff;
				long ofs = c & 127;
				while((c & 128) != 0) {
					ofs += 1;
					c = ib[p++] & 0xff;
					ofs <<= 7;
					ofs += (c & 127);
				}
				r.format = StoredObjectRepresentation.PACK_DELTA;
				r.baseId = rev.findObject(pos - ofs);
				r.length = len - p;
				return;
			}

			case Constants.OBJ_REF_DELTA: {
				readFully(pos + p, ib, 0, 20, ctx);
				r.format = StoredObjectRepresentation.PACK_DELTA;
				r.baseId = ObjectId.fromRaw(ib);
				r.length = len - p - 20;
				return;
			}

			default:
				throw new IOException(MessageFormat.format(
						JGitText.get().unknownObjectType, typeCode));
		}
	}

	boolean isCorrupt(long offset) {
		LongList list = corruptObjects;
		if(list == null) {
			return false;
		}
		synchronized(list) {
			return list.contains(offset);
		}
	}

	private void setCorrupt(long offset) {
		LongList list = corruptObjects;
		if(list == null) {
			synchronized(corruptObjectsLock) {
				list = corruptObjects;
				if(list == null) {
					list = new LongList();
					corruptObjects = list;
				}
			}
		}
		synchronized(list) {
			list.add(offset);
		}
	}

	private DfsBlockCache.Ref<PackIndex> loadPackIndex(
			DfsReader ctx, DfsStreamKey idxKey) throws IOException {
		try {
			try(ReadableChannel rc = ctx.db.openFile(desc, INDEX)) {
				InputStream in = Channels.newInputStream(rc);
				int wantSize = 8192;
				int bs = rc.blockSize();
				if(0 < bs && bs < wantSize) {
					bs = (wantSize / bs) * bs;
				} else if(bs <= 0) {
					bs = wantSize;
				}
				PackIndex idx = PackIndex.read(new BufferedInputStream(in, bs));
				index = idx;
				return new DfsBlockCache.Ref<>(
						idxKey,
						REF_POSITION,
						idx.getObjectCount() * REC_SIZE,
						idx);
			}
		} catch(EOFException e) {
			throw new IOException(MessageFormat.format(
					DfsText.get().shortReadOfIndex,
					desc.getFileName(INDEX)), e);
		} catch(IOException e) {
			throw new IOException(MessageFormat.format(
					DfsText.get().cannotReadIndex,
					desc.getFileName(INDEX)), e);
		}
	}

	private DfsBlockCache.Ref<PackReverseIndex> loadReverseIdx(
			DfsStreamKey revKey, PackIndex idx) {
		PackReverseIndex revidx = new PackReverseIndex(idx);
		reverseIndex = revidx;
		return new DfsBlockCache.Ref<>(
				revKey,
				REF_POSITION,
				idx.getObjectCount() * 8,
				revidx);
	}

	private DfsBlockCache.Ref<PackBitmapIndex> loadBitmapIndex(DfsReader ctx,
															   DfsStreamKey bitmapKey) throws IOException {
		try(ReadableChannel rc = ctx.db.openFile(desc, BITMAP_INDEX)) {
			long size;
			PackBitmapIndex bmidx;
			try {
				InputStream in = Channels.newInputStream(rc);
				int wantSize = 8192;
				int bs = rc.blockSize();
				if(0 < bs && bs < wantSize) {
					bs = (wantSize / bs) * bs;
				} else if(bs <= 0) {
					bs = wantSize;
				}
				in = new BufferedInputStream(in, bs);
				bmidx = PackBitmapIndex.read(in, () -> idx(ctx),
						() -> getReverseIdx(ctx),
						ctx.getOptions().shouldLoadRevIndexInParallel());
			} finally {
				size = rc.position();
			}
			bitmapIndex = bmidx;
			return new DfsBlockCache.Ref<>(
					bitmapKey, REF_POSITION, size, bmidx);
		} catch(EOFException e) {
			throw new IOException(MessageFormat.format(
					DfsText.get().shortReadOfIndex,
					desc.getFileName(BITMAP_INDEX)), e);
		} catch(IOException e) {
			throw new IOException(MessageFormat.format(
					DfsText.get().cannotReadIndex,
					desc.getFileName(BITMAP_INDEX)), e);
		}
	}
}
