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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import org.eclipse.jgit.internal.storage.pack.PackOutputStream;

final class DfsBlock {
	final DfsStreamKey stream;
	final long start;
	final long end;
	private final byte[] block;

	DfsBlock(DfsStreamKey p, long pos, byte[] buf) {
		stream = p;
		start = pos;
		end = pos + buf.length;
		block = buf;
	}

	int size() {
		return block.length;
	}

	ByteBuffer zeroCopyByteBuffer(int n) {
		ByteBuffer b = ByteBuffer.wrap(block);
		b.position(n);
		return b;
	}

	boolean contains(DfsStreamKey want, long pos) {
		return stream.equals(want) && start <= pos && pos < end;
	}

	int copy(long pos, byte[] dstbuf, int dstoff, int cnt) {
		int ptr = (int) (pos - start);
		return copy(ptr, dstbuf, dstoff, cnt);
	}

	int copy(int p, byte[] b, int o, int n) {
		n = Math.min(block.length - p, n);
		System.arraycopy(block, p, b, o, n);
		return n;
	}

	int setInput(long pos, Inflater inf) throws DataFormatException {
		int ptr = (int) (pos - start);
		int cnt = block.length - ptr;
		if(cnt <= 0) {
			throw new DataFormatException(cnt + " bytes to inflate:" + " at pos=" + pos
					+ "; block.start=" + start + "; ptr=" + ptr + "; block.length=" + block.length);
		}
		inf.setInput(block, ptr, cnt);
		return cnt;
	}

	void crc32(CRC32 out, long pos, int cnt) {
		int ptr = (int) (pos - start);
		out.update(block, ptr, cnt);
	}

	void write(PackOutputStream out, long pos, int cnt)
			throws IOException {
		out.write(block, (int) (pos - start), cnt);
	}

	void check(Inflater inf, long pos, int cnt) throws DataFormatException {
		inf.setInput(block, (int) (pos - start), cnt);
	}
}
