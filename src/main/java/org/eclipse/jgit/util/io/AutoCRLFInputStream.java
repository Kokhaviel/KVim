/*
 * Copyright (C) 2012, Robin Rosenberg
 * Copyright (C) 2010, 2013 Marc Strapetz <marc.strapetz@syntevo.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.util.io;

import java.io.IOException;
import java.io.InputStream;

import org.eclipse.jgit.diff.RawText;

public class AutoCRLFInputStream extends InputStream {

	private final byte[] single = new byte[1];
	private final byte[] buf = new byte[RawText.getBufferSize()];
	private final InputStream in;
	private int cnt;
	private int ptr;
	private boolean isBinary;
	private boolean detectBinary;
	private byte last;

	public AutoCRLFInputStream(InputStream in, boolean detectBinary) {
		this.in = in;
		this.detectBinary = detectBinary;
	}

	@Override
	public int read() throws IOException {
		final int read = read(single, 0, 1);
		return read == 1 ? single[0] & 0xff : -1;
	}

	@Override
	public int read(byte[] bs, int off, int len) throws IOException {
		if(len == 0)
			return 0;

		if(cnt == -1)
			return -1;

		int i = off;
		final int end = off + len;

		while(i < end) {
			if(ptr == cnt && !fillBuffer())
				break;

			byte b = buf[ptr++];
			if(isBinary || b != '\n') {
				bs[i++] = last = b;
				continue;
			}

			if(last == '\r') {
				bs[i++] = last = b;
				continue;
			}
			bs[i++] = last = '\r';
			ptr--;
		}
		int n = i == off ? -1 : i - off;
		if(n > 0)
			last = bs[i - 1];
		return n;
	}

	@Override
	public void close() throws IOException {
		in.close();
	}

	private boolean fillBuffer() throws IOException {
		cnt = 0;
		while(cnt < buf.length) {
			int n = in.read(buf, cnt, buf.length - cnt);
			if(n < 0) {
				break;
			}
			cnt += n;
		}
		if(cnt < 1) {
			cnt = -1;
			return false;
		}
		if(detectBinary) {
			isBinary = RawText.isBinary(buf, cnt, cnt < buf.length);
			detectBinary = false;
		}
		ptr = 0;
		return true;
	}
}
