/*
 * Copyright (C) 2015, Ivan Motsch <ivan.motsch@bsiag.com>
 * Copyright (C) 2020, Thomas Wolf <thomas.wolf@paranor.ch> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.util.io;

import java.io.IOException;
import java.io.OutputStream;

import org.eclipse.jgit.diff.RawText;

public class AutoLFOutputStream extends OutputStream {

	private final OutputStream out;
	private int buf = -1;
	private final byte[] binbuf = new byte[RawText.getBufferSize()];
	private final byte[] onebytebuf = new byte[1];
	private int binbufcnt = 0;
	private boolean detectBinary;
	private boolean isBinary;

	public AutoLFOutputStream(OutputStream out, boolean detectBinary) {
		this.out = out;
		this.detectBinary = detectBinary;
	}

	@Override
	public void write(int b) throws IOException {
		onebytebuf[0] = (byte) b;
		write(onebytebuf, 0, 1);
	}

	@Override
	public void write(byte[] b) throws IOException {
		int overflow = buffer(b, 0, b.length);
		if(overflow > 0) {
			write(b, b.length - overflow, overflow);
		}
	}

	@Override
	public void write(byte[] b, int startOff, int startLen)
			throws IOException {
		final int overflow = buffer(b, startOff, startLen);
		if(overflow <= 0) {
			return;
		}
		final int off = startOff + startLen - overflow;
		int lastw = off;
		if(isBinary) {
			out.write(b, off, overflow);
			return;
		}
		for(int i = off; i < off + overflow; ++i) {
			final byte c = b[i];
			switch(c) {
				case '\r':
					if(lastw < i) {
						out.write(b, lastw, i - lastw);
					}
					lastw = i + 1;
					buf = '\r';
					break;
				case '\n':
					if(buf == '\r') {
						out.write('\n');
						lastw = i + 1;
						buf = -1;
					} else {
						if(lastw < i + 1) {
							out.write(b, lastw, i + 1 - lastw);
						}
						lastw = i + 1;
					}
					break;
				default:
					if(buf == '\r') {
						out.write('\r');
						lastw = i;
					}
					buf = -1;
					break;
			}
		}
		if(lastw < off + overflow) {
			out.write(b, lastw, off + overflow - lastw);
		}
	}

	private int buffer(byte[] b, int off, int len) throws IOException {
		if(binbufcnt > binbuf.length) {
			return len;
		}
		int copy = Math.min(binbuf.length - binbufcnt, len);
		System.arraycopy(b, off, binbuf, binbufcnt, copy);
		binbufcnt += copy;
		int remaining = len - copy;
		if(remaining > 0) {
			decideMode(false);
		}
		return remaining;
	}

	private void decideMode(boolean complete) throws IOException {
		if(detectBinary) {
			isBinary = RawText.isBinary(binbuf, binbufcnt, complete);
			if(!isBinary) {
				isBinary = RawText.isCrLfText(binbuf, binbufcnt, complete);
			}
			detectBinary = false;
		}
		int cachedLen = binbufcnt;
		binbufcnt = binbuf.length + 1;
		write(binbuf, 0, cachedLen);
	}

	@Override
	public void flush() throws IOException {
		if(binbufcnt <= binbuf.length) {
			decideMode(true);
		}
		out.flush();
	}

	@Override
	public void close() throws IOException {
		flush();
		if(buf == '\r') {
			out.write(buf);
			buf = -1;
		}
		out.close();
	}
}