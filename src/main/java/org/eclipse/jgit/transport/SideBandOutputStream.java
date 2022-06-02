/*
 * Copyright (C) 2008-2010, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import java.io.IOException;
import java.io.OutputStream;
import java.text.MessageFormat;

import org.eclipse.jgit.internal.JGitText;

public class SideBandOutputStream extends OutputStream {

	public static final int CH_DATA = SideBandInputStream.CH_DATA;
	public static final int CH_PROGRESS = SideBandInputStream.CH_PROGRESS;
	public static final int CH_ERROR = SideBandInputStream.CH_ERROR;
	public static final int SMALL_BUF = 1000;
	public static final int MAX_BUF = 65520;
	static final int HDR_SIZE = 5;
	private final OutputStream out;
	private final byte[] buffer;
	private int cnt;

	public SideBandOutputStream(int chan, int sz, OutputStream os) {
		if(chan <= 0 || chan > 255)
			throw new IllegalArgumentException(MessageFormat.format(
					JGitText.get().channelMustBeInRange1_255, chan));
		if(sz <= HDR_SIZE)
			throw new IllegalArgumentException(MessageFormat.format(
					JGitText.get().packetSizeMustBeAtLeast, sz, HDR_SIZE));
		else if(MAX_BUF < sz)
			throw new IllegalArgumentException(MessageFormat.format(
					JGitText.get().packetSizeMustBeAtMost, sz, MAX_BUF));

		out = os;
		buffer = new byte[sz];
		buffer[4] = (byte) chan;
		cnt = HDR_SIZE;
	}

	void flushBuffer() throws IOException {
		if(HDR_SIZE < cnt)
			writeBuffer();
	}

	@Override
	public void flush() throws IOException {
		flushBuffer();
		out.flush();
	}

	@Override
	public void write(byte[] b, int off, int len) throws IOException {
		while(0 < len) {
			int capacity = buffer.length - cnt;
			if(cnt == HDR_SIZE && capacity < len) {
				PacketLineOut.formatLength(buffer, buffer.length);
				out.write(buffer, 0, HDR_SIZE);
				out.write(b, off, capacity);
				off += capacity;
				len -= capacity;

			} else {
				if(capacity == 0)
					writeBuffer();

				int n = Math.min(len, capacity);
				System.arraycopy(b, off, buffer, cnt, n);
				cnt += n;
				off += n;
				len -= n;
			}
		}
	}

	@Override
	public void write(int b) throws IOException {
		if(cnt == buffer.length)
			writeBuffer();
		buffer[cnt++] = (byte) b;
	}

	private void writeBuffer() throws IOException {
		PacketLineOut.formatLength(buffer, cnt);
		out.write(buffer, 0, cnt);
		cnt = HDR_SIZE;
	}
}
