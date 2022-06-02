/*
 * Copyright (C) 2010, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.pack;

import java.io.IOException;
import java.io.OutputStream;

import org.eclipse.jgit.lib.Constants;

public class DeltaEncoder {

	private static final int MAX_V2_COPY = 0x10000;
	private static final int MAX_COPY_CMD_SIZE = 8;
	private static final int MAX_INSERT_DATA_SIZE = 127;

	private final OutputStream out;
	private final byte[] buf = new byte[MAX_COPY_CMD_SIZE * 4];
	private final int limit;
	private int size;

	public DeltaEncoder(OutputStream out, long baseSize, long resultSize,
						int limit) throws IOException {
		this.out = out;
		this.limit = limit;
		writeVarint(baseSize);
		writeVarint(resultSize);
	}

	private void writeVarint(long sz) throws IOException {
		int p = 0;
		while(sz >= 0x80) {
			buf[p++] = (byte) (0x80 | (((int) sz) & 0x7f));
			sz >>>= 7;
		}
		buf[p++] = (byte) (((int) sz) & 0x7f);
		size += p;
		if(limit == 0 || size < limit)
			out.write(buf, 0, p);
	}

	public int getSize() {
		return size;
	}

	public boolean insert(String text) throws IOException {
		return insert(Constants.encode(text));
	}

	public boolean insert(byte[] text) throws IOException {
		return insert(text, 0, text.length);
	}

	public boolean insert(byte[] text, int off, int cnt)
			throws IOException {
		if(cnt <= 0)
			return true;
		if(limit != 0) {
			int hdrs = cnt / MAX_INSERT_DATA_SIZE;
			if(cnt % MAX_INSERT_DATA_SIZE != 0)
				hdrs++;
			if(limit < size + hdrs + cnt)
				return false;
		}
		do {
			int n = Math.min(MAX_INSERT_DATA_SIZE, cnt);
			out.write((byte) n);
			out.write(text, off, n);
			off += n;
			cnt -= n;
			size += 1 + n;
		} while(0 < cnt);
		return true;
	}

	public boolean copy(long offset, int cnt) throws IOException {
		if(cnt == 0)
			return true;

		int p = 0;

		while(MAX_V2_COPY < cnt) {
			p = encodeCopy(p, offset, MAX_V2_COPY);
			offset += MAX_V2_COPY;
			cnt -= MAX_V2_COPY;

			if(buf.length < p + MAX_COPY_CMD_SIZE) {
				if(limit != 0 && limit < size + p)
					return false;
				out.write(buf, 0, p);
				size += p;
				p = 0;
			}
		}

		p = encodeCopy(p, offset, cnt);
		if(limit != 0 && limit < size + p)
			return false;
		out.write(buf, 0, p);
		size += p;
		return true;
	}

	private int encodeCopy(int p, long offset, int cnt) {
		int cmd = 0x80;
		final int cmdPtr = p++;
		byte b;

		if((b = (byte) (offset & 0xff)) != 0) {
			cmd |= 0x01;
			buf[p++] = b;
		}
		if((b = (byte) ((offset >>> 8) & 0xff)) != 0) {
			cmd |= 0x02;
			buf[p++] = b;
		}
		if((b = (byte) ((offset >>> 16) & 0xff)) != 0) {
			cmd |= 0x04;
			buf[p++] = b;
		}
		if((b = (byte) ((offset >>> 24) & 0xff)) != 0) {
			cmd |= 0x08;
			buf[p++] = b;
		}

		if(cnt != MAX_V2_COPY) {
			if((b = (byte) (cnt & 0xff)) != 0) {
				cmd |= 0x10;
				buf[p++] = b;
			}
			if((b = (byte) ((cnt >>> 8) & 0xff)) != 0) {
				cmd |= 0x20;
				buf[p++] = b;
			}
			if((b = (byte) ((cnt >>> 16) & 0xff)) != 0) {
				cmd |= 0x40;
				buf[p++] = b;
			}
		}

		buf[cmdPtr] = (byte) cmd;
		return p;
	}
}
