/*
 * Copyright (C) 2008-2009, Google Inc.
 * Copyright (C) 2007, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2006-2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.file;

import static org.eclipse.jgit.internal.storage.pack.PackExt.INDEX;
import static org.eclipse.jgit.internal.storage.pack.PackExt.KEEP;

import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.LargeObjectException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.NoPackSignatureException;
import org.eclipse.jgit.errors.PackInvalidException;
import org.eclipse.jgit.errors.PackMismatchException;
import org.eclipse.jgit.errors.StoredObjectRepresentationNotAvailableException;
import org.eclipse.jgit.errors.UnpackException;
import org.eclipse.jgit.errors.UnsupportedPackIndexVersionException;
import org.eclipse.jgit.errors.UnsupportedPackVersionException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.internal.storage.pack.BinaryDelta;
import org.eclipse.jgit.internal.storage.pack.PackOutputStream;
import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.util.LongList;
import org.eclipse.jgit.util.NB;
import org.eclipse.jgit.util.RawParseUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Pack implements Iterable<PackIndex.MutableEntry> {
	private static final Logger LOG = LoggerFactory.getLogger(Pack.class);
	public static final Comparator<Pack> SORT = (a, b) -> b.packLastModified.compareTo(a.packLastModified);

	private final PackFile packFile;
	private PackFile keepFile;
	final int hash;
	private RandomAccessFile fd;
	private final Object readLock = new Object();
	long length;
	private int activeWindows;
	private int activeCopyRawData;
	Instant packLastModified;
	private final PackFileSnapshot fileSnapshot;
	private volatile boolean invalid;
	private volatile Exception invalidatingCause;

	@Nullable
	private PackFile bitmapIdxFile;
	private final AtomicInteger transientErrorCount = new AtomicInteger();
	private byte[] packChecksum;
	private volatile PackIndex loadedIdx;
	private PackReverseIndex reverseIdx;
	private PackBitmapIndex bitmapIdx;
	private volatile LongList corruptObjects;

	public Pack(File packFile, @Nullable PackFile bitmapIdxFile) {
		this.packFile = new PackFile(packFile);
		this.fileSnapshot = PackFileSnapshot.save(packFile);
		this.packLastModified = fileSnapshot.lastModifiedInstant();
		this.bitmapIdxFile = bitmapIdxFile;

		hash = System.identityHashCode(this) * 31;
		length = Long.MAX_VALUE;
	}

	private PackIndex idx() throws IOException {
		PackIndex idx = loadedIdx;
		if(idx == null) {
			synchronized(this) {
				idx = loadedIdx;
				if(idx == null) {
					if(invalid) {
						throw new PackInvalidException(packFile,
								invalidatingCause);
					}
					try {
						long start = System.currentTimeMillis();
						PackFile idxFile = packFile.create(INDEX);
						idx = PackIndex.open(idxFile);
						if(LOG.isDebugEnabled()) {
							LOG.debug(String.format(
									"Opening pack index %s, size %.3f MB took %d ms",
									idxFile.getAbsolutePath(),
									idxFile.length()
											/ (1024f * 1024),
									System.currentTimeMillis()
											- start));
						}

						if(packChecksum == null) {
							packChecksum = idx.packChecksum;
							fileSnapshot.setChecksum(ObjectId.fromRaw(packChecksum));
						} else if(!Arrays.equals(packChecksum, idx.packChecksum)) {
							throw new PackMismatchException(MessageFormat.format(JGitText.get().packChecksumMismatch,
									packFile.getPath(),
									ObjectId.fromRaw(packChecksum).name(),
									ObjectId.fromRaw(idx.packChecksum).name()));
						}
						loadedIdx = idx;
					} catch(InterruptedIOException e) {
						throw e;
					} catch(IOException e) {
						invalid = true;
						invalidatingCause = e;
						throw e;
					}
				}
			}
		}
		return idx;
	}

	public PackFile getPackFile() {
		return packFile;
	}

	public PackIndex getIndex() throws IOException {
		return idx();
	}

	public String getPackName() {
		return packFile.getId();
	}

	public boolean hasObject(AnyObjectId id) throws IOException {
		final long offset = idx().findOffset(id);
		return 0 < offset && !isCorrupt(offset);
	}

	public boolean shouldBeKept() {
		if(keepFile == null) {
			keepFile = packFile.create(KEEP);
		}
		return keepFile.exists();
	}

	ObjectLoader get(WindowCursor curs, AnyObjectId id)
			throws IOException {
		final long offset = idx().findOffset(id);
		return 0 < offset && !isCorrupt(offset) ? load(curs, offset) : null;
	}

	void resolve(Set<ObjectId> matches, AbbreviatedObjectId id, int matchLimit)
			throws IOException {
		idx().resolve(matches, id, matchLimit);
	}

	public void close() {
		WindowCache.purge(this);
		synchronized(this) {
			loadedIdx = null;
			reverseIdx = null;
		}
	}

	@Override
	public Iterator<PackIndex.MutableEntry> iterator() {
		try {
			return idx().iterator();
		} catch(IOException e) {
			return Collections.emptyIterator();
		}
	}

	long getObjectCount() throws IOException {
		return idx().getObjectCount();
	}

	ObjectId findObjectForOffset(long offset) throws IOException {
		return getReverseIdx().findObject(offset);
	}

	PackFileSnapshot getFileSnapshot() {
		return fileSnapshot;
	}

	private byte[] decompress(final long position, final int sz,
							  final WindowCursor curs) throws IOException, DataFormatException {
		byte[] dstbuf;
		try {
			dstbuf = new byte[sz];
		} catch(OutOfMemoryError noMemory) {
			return null;
		}

		if(curs.inflate(this, position, dstbuf, false) != sz)
			throw new EOFException(MessageFormat.format(
					JGitText.get().shortCompressedStreamAt,
					position));
		return dstbuf;
	}

	void copyPackAsIs(PackOutputStream out, WindowCursor curs) throws IOException {
		curs.pin(this, 0);
		curs.copyPackAsIs(this, length, out);
	}

	final void copyAsIs(PackOutputStream out, LocalObjectToPack src,
						boolean validate, WindowCursor curs) throws IOException,
			StoredObjectRepresentationNotAvailableException {
		beginCopyAsIs();
		try {
			copyAsIs2(out, src, validate, curs);
		} finally {
			endCopyAsIs();
		}
	}

	private void copyAsIs2(PackOutputStream out, LocalObjectToPack src,
						   boolean validate, WindowCursor curs) throws IOException,
			StoredObjectRepresentationNotAvailableException {
		final CRC32 crc1 = validate ? new CRC32() : null;
		final CRC32 crc2 = validate ? new CRC32() : null;
		final byte[] buf = out.getCopyBuffer();

		readFully(src.offset, buf, 20, curs);
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

			readFully(src.offset + headerCnt, buf, 20, curs);
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
		final ByteArrayWindow quickCopy;

		try {
			quickCopy = curs.quickCopy(this, dataOffset, dataLength);

			if(validate && idx().hasCRC32Support()) {
				expectedCRC = idx().findCRC32(src);
				if(quickCopy != null) {
					quickCopy.crc32(crc1, dataOffset, (int) dataLength);
				} else {
					long pos = dataOffset;
					long cnt = dataLength;
					while(cnt > 0) {
						final int n = (int) Math.min(cnt, buf.length);
						readFully(pos, buf, n, curs);
						crc1.update(buf, 0, n);
						pos += n;
						cnt -= n;
					}
				}
				if(crc1.getValue() != expectedCRC) {
					setCorrupt(src.offset);
					throw new CorruptObjectException(MessageFormat.format(
							JGitText.get().objectAtHasBadZlibStream,
							src.offset, getPackFile()));
				}
			} else if(validate) {
				Inflater inf = curs.inflater();
				byte[] tmp = new byte[1024];
				if(quickCopy != null) {
					quickCopy.check(inf, dataOffset, (int) dataLength);
				} else {
					long pos = dataOffset;
					long cnt = dataLength;
					while(cnt > 0) {
						final int n = (int) Math.min(cnt, buf.length);
						readFully(pos, buf, n, curs);
						crc1.update(buf, 0, n);
						inf.setInput(buf, 0, n);
						pos += n;
						cnt -= n;
					}
				}
				if(!inf.finished() || inf.getBytesRead() != dataLength) {
					setCorrupt(src.offset);
					throw new EOFException(MessageFormat.format(
							JGitText.get().shortCompressedStreamAt,
							src.offset));
				}
				expectedCRC = crc1.getValue();
			} else {
				expectedCRC = -1;
			}
		} catch(DataFormatException dataFormat) {
			setCorrupt(src.offset);

			CorruptObjectException corruptObject = new CorruptObjectException(
					MessageFormat.format(
							JGitText.get().objectAtHasBadZlibStream,
							src.offset, getPackFile()),
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
					readFully(pos, buf, n, curs);
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
				readFully(pos, buf, n, curs);
				if(validate) {
					crc2.update(buf, 0, n);
				}
				out.write(buf, 0, n);
				pos += n;
				cnt -= n;
			}
			if(validate) {
				if(crc2.getValue() != expectedCRC) {
					throw new CorruptObjectException(MessageFormat.format(
							JGitText.get().objectAtHasBadZlibStream,
							src.offset, getPackFile()));
				}
			}
		}
	}

	boolean invalid() {
		return invalid;
	}

	int incrementTransientErrorCount() {
		return transientErrorCount.incrementAndGet();
	}

	void resetTransientErrorCount() {
		transientErrorCount.set(0);
	}

	private void readFully(final long position, final byte[] dstbuf,
						   final int cnt, final WindowCursor curs)
			throws IOException {
		if(curs.copy(this, position, dstbuf, 0, cnt) != cnt)
			throw new EOFException();
	}

	private synchronized void beginCopyAsIs()
			throws StoredObjectRepresentationNotAvailableException {
		if(++activeCopyRawData == 1 && activeWindows == 0) {
			try {
				doOpen();
			} catch(IOException thisPackNotValid) {
				throw new StoredObjectRepresentationNotAvailableException(
						thisPackNotValid);
			}
		}
	}

	private synchronized void endCopyAsIs() {
		if(--activeCopyRawData == 0 && activeWindows == 0)
			doClose();
	}

	synchronized boolean beginWindowCache() throws IOException {
		if(++activeWindows == 1) {
			if(activeCopyRawData == 0)
				doOpen();
			return true;
		}
		return false;
	}

	synchronized boolean endWindowCache() {
		final boolean r = --activeWindows == 0;
		if(r && activeCopyRawData == 0)
			doClose();
		return r;
	}

	private void doOpen() throws IOException {
		if(invalid) {
			openFail(true, invalidatingCause);
			throw new PackInvalidException(packFile, invalidatingCause);
		}
		try {
			synchronized(readLock) {
				fd = new RandomAccessFile(packFile, "r");
				length = fd.length();
				onOpenPack();
			}
		} catch(FileNotFoundException fn) {
			openFail(!packFile.exists(), fn);
			throw fn;
		} catch(EOFException | AccessDeniedException | NoSuchFileException
				| CorruptObjectException | NoPackSignatureException
				| PackMismatchException | UnpackException
				| UnsupportedPackIndexVersionException
				| UnsupportedPackVersionException pe) {
			openFail(true, pe);
			throw pe;
		} catch(IOException | RuntimeException e) {
			openFail(false, e);
			throw e;
		}
	}

	private void openFail(boolean invalidate, Exception cause) {
		activeWindows = 0;
		activeCopyRawData = 0;
		invalid = invalidate;
		invalidatingCause = cause;
		doClose();
	}

	private void doClose() {
		synchronized(readLock) {
			if(fd != null) {
				try {
					fd.close();
				} catch(IOException ignored) {
				}
				fd = null;
			}
		}
	}

	ByteArrayWindow read(long pos, int size) throws IOException {
		synchronized(readLock) {
			if(invalid || fd == null) {
				throw new PackInvalidException(packFile, invalidatingCause);
			}
			if(length < pos + size)
				size = (int) (length - pos);
			final byte[] buf = new byte[size];
			fd.seek(pos);
			fd.readFully(buf, 0, size);
			return new ByteArrayWindow(this, pos, buf);
		}
	}

	ByteWindow mmap(long pos, int size) throws IOException {
		synchronized(readLock) {
			if(length < pos + size)
				size = (int) (length - pos);

			MappedByteBuffer map;
			try {
				map = fd.getChannel().map(MapMode.READ_ONLY, pos, size);
			} catch(IOException ioe1) {
				System.gc();
				System.runFinalization();
				map = fd.getChannel().map(MapMode.READ_ONLY, pos, size);
			}

			if(map.hasArray())
				return new ByteArrayWindow(this, pos, map.array());
			return new ByteBufferWindow(this, pos, map);
		}
	}

	private void onOpenPack() throws IOException {
		final PackIndex idx = idx();
		final byte[] buf = new byte[20];

		fd.seek(0);
		fd.readFully(buf, 0, 12);
		if(RawParseUtils.match(buf, 0, Constants.PACK_SIGNATURE) != 4) {
			throw new NoPackSignatureException(JGitText.get().notAPACKFile);
		}
		final long vers = NB.decodeUInt32(buf, 4);
		final long packCnt = NB.decodeUInt32(buf, 8);
		if(vers != 2 && vers != 3) {
			throw new UnsupportedPackVersionException(vers);
		}

		if(packCnt != idx.getObjectCount()) {
			throw new PackMismatchException(MessageFormat.format(
					JGitText.get().packObjectCountMismatch,
					packCnt, idx.getObjectCount(),
					getPackFile()));
		}

		fd.seek(length - 20);
		fd.readFully(buf, 0, 20);
		if(!Arrays.equals(buf, packChecksum)) {
			throw new PackMismatchException(MessageFormat.format(
					JGitText.get().packChecksumMismatch,
					getPackFile(),
					ObjectId.fromRaw(buf).name(),
					ObjectId.fromRaw(idx.packChecksum).name()));
		}
	}

	ObjectLoader load(WindowCursor curs, long pos)
			throws IOException, LargeObjectException {
		try {
			final byte[] ib = curs.tempId;
			Delta delta = null;
			byte[] data = null;
			int type = Constants.OBJ_BAD;
			boolean cached = false;

			SEARCH:
			for(; ; ) {
				readFully(pos, ib, 20, curs);
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
						if(delta != null || sz < curs.getStreamFileThreshold()) {
							data = decompress(pos + p, (int) sz, curs);
						}

						if(delta != null) {
							type = typeCode;
							break SEARCH;
						}

						if(data != null) {
							return new ObjectLoader.SmallObject(typeCode, data);
						}
						return new LargePackedWholeObject(typeCode, sz, pos, p,
								this, curs.db);
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
						if(sz != delta.deltaSize)
							break SEARCH;

						DeltaBaseCache.Entry e = curs.getDeltaBaseCache().get(this, base);
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
						readFully(pos + p, ib, 20, curs);
						long base = findDeltaBase(ObjectId.fromRaw(ib));
						delta = new Delta(delta, pos, (int) sz, p + 20, base);
						if(sz != delta.deltaSize)
							break SEARCH;

						DeltaBaseCache.Entry e = curs.getDeltaBaseCache().get(this, base);
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
						throw new IOException(MessageFormat.format(
								JGitText.get().unknownObjectType,
								typeCode));
				}
			}

			if(data == null)
				throw new IOException(JGitText.get().inMemoryBufferLimitExceeded);

			do {
				if(cached)
					cached = false;
				else if(delta.next == null)
					curs.getDeltaBaseCache().store(this, delta.basePos, data, type);

				pos = delta.deltaPos;

				final byte[] cmds = decompress(pos + delta.hdrLen,
						delta.deltaSize, curs);
				if(cmds == null) {
					throw new LargeObjectException.OutOfMemory(new OutOfMemoryError());
				}

				final long sz = BinaryDelta.getResultSize(cmds);
				if(Integer.MAX_VALUE <= sz)
					throw new LargeObjectException.ExceedsByteArrayLimit();

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
					MessageFormat.format(
							JGitText.get().objectAtHasBadZlibStream,
							pos, getPackFile()),
					dfe);
		}
	}

	private long findDeltaBase(ObjectId baseId) throws IOException {
		long ofs = idx().findOffset(baseId);
		if(ofs < 0)
			throw new MissingObjectException(baseId,
					JGitText.get().missingDeltaBase);
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

	byte[] getDeltaHeader(WindowCursor wc, long pos) throws IOException, DataFormatException {
		final byte[] hdr = new byte[18];
		wc.inflate(this, pos, hdr, true);
		return hdr;
	}

	long getObjectSize(WindowCursor curs, AnyObjectId id)
			throws IOException {
		final long offset = idx().findOffset(id);
		return 0 < offset ? getObjectSize(curs, offset) : -1;
	}

	long getObjectSize(WindowCursor curs, long pos)
			throws IOException {
		final byte[] ib = curs.tempId;
		readFully(pos, ib, 20, curs);
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
				while((c & 128) != 0)
					c = ib[p++] & 0xff;
				deltaAt = pos + p;
				break;

			case Constants.OBJ_REF_DELTA:
				deltaAt = pos + p + 20;
				break;

			default:
				throw new IOException(MessageFormat.format(
						JGitText.get().unknownObjectType, type));
		}

		try {
			return BinaryDelta.getResultSize(getDeltaHeader(curs, deltaAt));
		} catch(DataFormatException e) {
			throw new CorruptObjectException(MessageFormat.format(
					JGitText.get().objectAtHasBadZlibStream, pos,
					getPackFile()), e);
		}
	}

	LocalObjectRepresentation representation(final WindowCursor curs,
											 final AnyObjectId objectId) throws IOException {
		final long pos = idx().findOffset(objectId);
		if(pos < 0)
			return null;

		final byte[] ib = curs.tempId;
		readFully(pos, ib, 20, curs);
		int c = ib[0] & 0xff;
		int p = 1;
		final int typeCode = (c >> 4) & 7;
		while((c & 0x80) != 0)
			c = ib[p++] & 0xff;

		long len = (findEndOffset(pos) - pos);
		switch(typeCode) {
			case Constants.OBJ_COMMIT:
			case Constants.OBJ_TREE:
			case Constants.OBJ_BLOB:
			case Constants.OBJ_TAG:
				return LocalObjectRepresentation.newWhole(this, pos, len - p);

			case Constants.OBJ_OFS_DELTA: {
				c = ib[p++] & 0xff;
				long ofs = c & 127;
				while((c & 128) != 0) {
					ofs += 1;
					c = ib[p++] & 0xff;
					ofs <<= 7;
					ofs += (c & 127);
				}
				ofs = pos - ofs;
				return LocalObjectRepresentation.newDelta(this, pos, len - p, ofs);
			}

			case Constants.OBJ_REF_DELTA: {
				len -= p;
				len -= Constants.OBJECT_ID_LENGTH;
				readFully(pos + p, ib, 20, curs);
				ObjectId id = ObjectId.fromRaw(ib);
				return LocalObjectRepresentation.newDelta(this, pos, len, id);
			}

			default:
				throw new IOException(
						MessageFormat.format(JGitText.get().unknownObjectType,
								typeCode));
		}
	}

	private long findEndOffset(long startOffset)
			throws IOException {
		final long maxOffset = length - 20;
		return getReverseIdx().findNextOffset(startOffset, maxOffset);
	}

	synchronized PackBitmapIndex getBitmapIndex() throws IOException {
		if(invalid || bitmapIdxFile == null) {
			return null;
		}
		if(bitmapIdx == null) {
			final PackBitmapIndex idx;
			try {
				idx = PackBitmapIndex.open(bitmapIdxFile, idx(),
						getReverseIdx());
			} catch(FileNotFoundException e) {
				bitmapIdxFile = null;
				return null;
			}

			if(Arrays.equals(packChecksum, idx.packChecksum)) {
				bitmapIdx = idx;
			} else {
				bitmapIdxFile = null;
			}
		}
		return bitmapIdx;
	}

	private synchronized PackReverseIndex getReverseIdx() throws IOException {
		if(reverseIdx == null)
			reverseIdx = new PackReverseIndex(idx());
		return reverseIdx;
	}

	private boolean isCorrupt(long offset) {
		LongList list = corruptObjects;
		if(list == null)
			return false;
		synchronized(list) {
			return list.contains(offset);
		}
	}

	private void setCorrupt(long offset) {
		LongList list = corruptObjects;
		if(list == null) {
			synchronized(readLock) {
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

	@Override
	public String toString() {
		return "Pack [packFileName=" + packFile.getName() + ", length="
				+ packFile.length() + ", packChecksum="
				+ ObjectId.fromRaw(packChecksum).name() + "]";
	}
}
