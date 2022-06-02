/*
 * Copyright (C) 2017, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.dfs;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.MessageFormat;

import org.eclipse.jgit.errors.PackInvalidException;
import org.eclipse.jgit.internal.storage.pack.PackExt;

abstract class BlockBasedFile {

	final DfsBlockCache cache;
	final DfsStreamKey key;
	final DfsPackDescription desc;
	final PackExt ext;

	volatile int blockSize;
	volatile long length;
	volatile boolean invalid;
	protected volatile Exception invalidatingCause;

	BlockBasedFile(DfsBlockCache cache, DfsPackDescription desc, PackExt ext) {
		this.cache = cache;
		this.key = desc.getStreamKey(ext);
		this.desc = desc;
		this.ext = ext;
	}

	String getFileName() {
		return desc.getFileName(ext);
	}

	boolean invalid() {
		return invalid;
	}

	void setBlockSize(int newSize) {
		blockSize = newSize;
	}

	long alignToBlock(long pos) {
		int size = blockSize;
		if(size == 0)
			size = cache.getBlockSize();
		return (pos / size) * size;
	}

	int blockSize(ReadableChannel rc) {
		int size = blockSize;
		if(size == 0) {
			size = rc.blockSize();
			if(size <= 0)
				size = cache.getBlockSize();
			else if(size < cache.getBlockSize())
				size = (cache.getBlockSize() / size) * size;
			blockSize = size;
		}
		return size;
	}

	DfsBlock getOrLoadBlock(long pos, DfsReader ctx) throws IOException {
		try(LazyChannel c = new LazyChannel(ctx, desc, ext)) {
			return cache.getOrLoad(this, pos, c);
		}
	}

	DfsBlock readOneBlock(long pos, ReadableChannel rc)
			throws IOException {
		if(invalid) {
			throw new PackInvalidException(getFileName(), invalidatingCause);
		}

		int size = blockSize(rc);
		pos = (pos / size) * size;

		long len = length;
		if(len < 0) {
			len = rc.size();
			if(0 <= len)
				length = len;
		}

		if(0 <= len && len < pos + size)
			size = (int) (len - pos);
		if(size <= 0)
			throw new EOFException(MessageFormat.format(
					DfsText.get().shortReadOfBlock, pos, getFileName(), 0L, 0L));

		byte[] buf = new byte[size];
		rc.position(pos);
		int cnt = read(rc, ByteBuffer.wrap(buf, 0, size));
		if(cnt != size) {
			if(0 <= len) {
				throw new EOFException(MessageFormat.format(
						DfsText.get().shortReadOfBlock, pos, getFileName(), size, cnt));
			}

			byte[] n = new byte[cnt];
			System.arraycopy(buf, 0, n, 0, n.length);
			buf = n;
		} else if(len < 0) {
			length = rc.size();
		}

		return new DfsBlock(key, pos, buf);
	}

	static int read(ReadableChannel rc, ByteBuffer buf) throws IOException {
		int n;
		do {
			n = rc.read(buf);
		} while(0 < n && buf.hasRemaining());
		return buf.position();
	}

	private static class LazyChannel
			implements AutoCloseable, DfsBlockCache.ReadableChannelSupplier {
		private final DfsReader ctx;
		private final DfsPackDescription desc;
		private final PackExt ext;

		private ReadableChannel rc;

		LazyChannel(DfsReader ctx, DfsPackDescription desc, PackExt ext) {
			this.ctx = ctx;
			this.desc = desc;
			this.ext = ext;
		}

		@Override
		public ReadableChannel get() throws IOException {
			if(rc == null) {
				rc = ctx.db.openFile(desc, ext);
			}
			return rc;
		}

		@Override
		public void close() throws IOException {
			if(rc != null) {
				rc.close();
			}
		}
	}
}
