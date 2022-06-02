/*
 * Copyright (C) 2011, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.dfs;

import static org.eclipse.jgit.internal.storage.pack.PackExt.PACK;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import org.eclipse.jgit.internal.storage.file.PackIndex;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.transport.PackLock;
import org.eclipse.jgit.transport.PackParser;
import org.eclipse.jgit.transport.PackedObjectInfo;

public class DfsPackParser extends PackParser {
	private final DfsObjDatabase objdb;

	private final DfsInserter objins;
	private final CRC32 crc;
	private final MessageDigest packDigest;
	private int blockSize;
	private long packEnd;
	private byte[] packHash;
	private Deflater def;
	private boolean isEmptyPack;
	private DfsPackDescription packDsc;
	private DfsStreamKey packKey;
	private PackIndex packIndex;
	private DfsOutputStream out;
	private byte[] currBuf;
	private long currPos;
	private int currEnd;
	private DfsBlockCache blockCache;
	private long readPos;
	private DfsBlock readBlock;

	protected DfsPackParser(DfsObjDatabase db, DfsInserter ins, InputStream in) {
		super(db, in);
		this.objdb = db;
		this.objins = ins;
		this.crc = new CRC32();
		this.packDigest = Constants.newMessageDigest();
	}

	@Override
	public PackLock parse(ProgressMonitor receiving, ProgressMonitor resolving)
			throws IOException {
		boolean rollback = true;
		try {
			blockCache = DfsBlockCache.getInstance();
			super.parse(receiving, resolving);
			if(isEmptyPack)
				return null;
			buffer(packHash, 0, packHash.length);
			if(currEnd != 0)
				flushBlock();
			out.close();
			out = null;
			currBuf = null;
			readBlock = null;
			packDsc.addFileExt(PACK);
			packDsc.setFileSize(PACK, packEnd);
			packDsc.setBlockSize(PACK, blockSize);

			writePackIndex();
			objdb.commitPack(Collections.singletonList(packDsc), null);
			rollback = false;

			DfsPackFile p = new DfsPackFile(blockCache, packDsc);
			p.setBlockSize(blockSize);
			if(packIndex != null)
				p.setPackIndex(packIndex);

			objdb.addPack(p);

			return null;
		} finally {
			blockCache = null;
			currBuf = null;
			readBlock = null;

			if(def != null) {
				def.end();
				def = null;
			}

			if(out != null) {
				try {
					out.close();
				} catch(IOException ignored) {
				}
				out = null;
			}

			if(rollback && packDsc != null) {
				try {
					objdb.rollbackPack(Collections.singletonList(packDsc));
				} finally {
					packDsc = null;
				}
			}
		}
	}

	@Override
	protected void onPackHeader(long objectCount) throws IOException {
		if(objectCount == 0) {
			isEmptyPack = true;
			currBuf = new byte[256];
			return;
		}

		packDsc = objdb.newPack(DfsObjDatabase.PackSource.RECEIVE);
		out = objdb.writeFile(packDsc, PACK);
		packKey = packDsc.getStreamKey(PACK);

		int size = out.blockSize();
		if(size <= 0)
			size = blockCache.getBlockSize();
		else if(size < blockCache.getBlockSize())
			size = (blockCache.getBlockSize() / size) * size;
		blockSize = size;
		currBuf = new byte[blockSize];
	}

	@Override
	protected void onBeginWholeObject(long streamPosition, int type, long inflatedSize) {
		crc.reset();
	}

	@Override
	protected void onEndWholeObject(PackedObjectInfo info) {
		info.setCRC((int) crc.getValue());
	}

	@Override
	protected void onBeginOfsDelta(long streamPosition, long baseStreamPosition, long inflatedSize) {
		crc.reset();
	}

	@Override
	protected void onBeginRefDelta(long streamPosition, AnyObjectId baseId,
								   long inflatedSize) {
		crc.reset();
	}

	@Override
	protected UnresolvedDelta onEndDelta() {
		UnresolvedDelta delta = new UnresolvedDelta();
		delta.setCRC((int) crc.getValue());
		return delta;
	}

	@Override
	protected void onInflatedObjectData(PackedObjectInfo obj, int typeCode, byte[] data) {
	}

	@Override
	protected void onObjectHeader(Source src, byte[] raw, int len) {
		crc.update(raw, 0, len);
	}

	@Override
	protected void onObjectData(Source src, byte[] raw, int pos, int len) {
		crc.update(raw, pos, len);
	}

	@Override
	protected void onStoreStream(byte[] raw, int len)
			throws IOException {
		buffer(raw, 0, len);
		packDigest.update(raw, 0, len);
	}

	private void buffer(byte[] raw, int pos, int len) throws IOException {
		while(0 < len) {
			int n = Math.min(len, currBuf.length - currEnd);
			if(n == 0) {
				DfsBlock v = flushBlock();
				currBuf = new byte[blockSize];
				currEnd = 0;
				currPos += v.size();
				continue;
			}

			System.arraycopy(raw, pos, currBuf, currEnd, n);
			pos += n;
			len -= n;
			currEnd += n;
			packEnd += n;
		}
	}

	private DfsBlock flushBlock() throws IOException {
		if(isEmptyPack)
			throw new IOException(DfsText.get().willNotStoreEmptyPack);

		out.write(currBuf, 0, currEnd);

		byte[] buf;
		if(currEnd == currBuf.length) {
			buf = currBuf;
		} else {
			buf = new byte[currEnd];
			System.arraycopy(currBuf, 0, buf, 0, currEnd);
		}

		DfsBlock v = new DfsBlock(packKey, currPos, buf);
		readBlock = v;
		blockCache.put(v);
		return v;
	}

	@Override
	protected void onPackFooter(byte[] hash) {
		packHash = hash;
	}

	@Override
	protected ObjectTypeAndSize seekDatabase(PackedObjectInfo obj,
											 ObjectTypeAndSize info) throws IOException {
		readPos = obj.getOffset();
		crc.reset();
		return readObjectHeader(info);
	}

	@Override
	protected ObjectTypeAndSize seekDatabase(UnresolvedDelta delta,
											 ObjectTypeAndSize info) throws IOException {
		readPos = delta.getOffset();
		crc.reset();
		return readObjectHeader(info);
	}

	@Override
	protected int readDatabase(byte[] dst, int pos, int cnt) throws IOException {
		if(cnt == 0)
			return 0;

		if(currPos <= readPos) {
			int p = (int) (readPos - currPos);
			int n = Math.min(cnt, currEnd - p);
			if(n == 0)
				throw new EOFException();
			System.arraycopy(currBuf, p, dst, pos, n);
			readPos += n;
			return n;
		}

		if(readBlock == null || !readBlock.contains(packKey, readPos)) {
			long start = toBlockStart(readPos);
			readBlock = blockCache.get(packKey, start);
			if(readBlock == null) {
				int size = (int) Math.min(blockSize, packEnd - start);
				byte[] buf = new byte[size];
				if(read(start, buf, 0, size) != size)
					throw new EOFException();
				readBlock = new DfsBlock(packKey, start, buf);
				blockCache.put(readBlock);
			}
		}

		int n = readBlock.copy(readPos, dst, pos, cnt);
		readPos += n;
		return n;
	}

	private int read(long pos, byte[] dst, int off, int len) throws IOException {
		if(len == 0)
			return 0;

		int cnt = 0;
		while(0 < len) {
			int r = out.read(pos, ByteBuffer.wrap(dst, off, len));
			if(r <= 0)
				break;
			pos += r;
			off += r;
			len -= r;
			cnt += r;
		}
		return cnt != 0 ? cnt : -1;
	}

	private long toBlockStart(long pos) {
		return (pos / blockSize) * blockSize;
	}

	@Override
	protected boolean checkCRC(int oldCRC) {
		return oldCRC == (int) crc.getValue();
	}

	@Override
	protected boolean onAppendBase(final int typeCode, final byte[] data,
								   final PackedObjectInfo info) throws IOException {
		info.setOffset(packEnd);

		final byte[] buf = buffer();
		int sz = data.length;
		int len = 0;
		buf[len++] = (byte) ((typeCode << 4) | (sz & 15));
		sz >>>= 4;
		while(sz > 0) {
			buf[len - 1] |= (byte) 0x80;
			buf[len++] = (byte) (sz & 0x7f);
			sz >>>= 7;
		}

		packDigest.update(buf, 0, len);
		crc.reset();
		crc.update(buf, 0, len);
		buffer(buf, 0, len);

		if(def == null)
			def = new Deflater(Deflater.DEFAULT_COMPRESSION, false);
		else
			def.reset();
		def.setInput(data);
		def.finish();

		while(!def.finished()) {
			len = def.deflate(buf);
			packDigest.update(buf, 0, len);
			crc.update(buf, 0, len);
			buffer(buf, 0, len);
		}

		info.setCRC((int) crc.getValue());
		return true;
	}

	@Override
	protected void onEndThinPack() {
		packHash = packDigest.digest();
	}

	private void writePackIndex() throws IOException {
		List<PackedObjectInfo> list = getSortedObjectList(null);
		packIndex = objins.writePackIndex(packDsc, packHash, list);
	}
}
