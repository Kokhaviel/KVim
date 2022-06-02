/*
 * Copyright (C) 2010, 2013 Marc Strapetz <marc.strapetz@syntevo.com>
 * Copyright (C) 2015, 2020 Ivan Motsch <ivan.motsch@bsiag.com> and others
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
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

import org.eclipse.jgit.diff.RawText;

public class AutoLFInputStream extends InputStream {

	public enum StreamFlag {
		DETECT_BINARY,
		ABORT_IF_BINARY,
		FOR_CHECKOUT
	}

	private final byte[] single = new byte[1];

	private final byte[] buf = new byte[RawText.getBufferSize()];

	private final InputStream in;

	private int cnt;

	private int ptr;

	private boolean passAsIs;

	private boolean isBinary;

	private boolean detectBinary;

	private final boolean abortIfBinary;

	private final boolean forCheckout;

	public static class IsBinaryException extends IOException {
		private static final long serialVersionUID = 1L;

		IsBinaryException() {
			super();
		}
	}

	public static AutoLFInputStream create(InputStream in,
										   StreamFlag... flags) {
		if(flags == null) {
			return new AutoLFInputStream(in, null);
		}
		EnumSet<StreamFlag> set = EnumSet.noneOf(StreamFlag.class);
		set.addAll(Arrays.asList(flags));
		return new AutoLFInputStream(in, set);
	}

	public AutoLFInputStream(InputStream in, Set<StreamFlag> flags) {
		this.in = in;
		this.detectBinary = flags != null
				&& flags.contains(StreamFlag.DETECT_BINARY);
		this.abortIfBinary = flags != null
				&& flags.contains(StreamFlag.ABORT_IF_BINARY);
		this.forCheckout = flags != null
				&& flags.contains(StreamFlag.FOR_CHECKOUT);
	}

	@Override
	public int read() throws IOException {
		final int read = read(single, 0, 1);
		return read == 1 ? single[0] & 0xff : -1;
	}

	@Override
	public int read(byte[] bs, int off, int len)
			throws IOException {
		if(len == 0)
			return 0;

		if(cnt == -1)
			return -1;

		int i = off;
		final int end = off + len;

		while(i < end) {
			if(ptr == cnt && !fillBuffer()) {
				break;
			}

			byte b = buf[ptr++];
			if(passAsIs || b != '\r') {
				bs[i++] = b;
				continue;
			}

			if(ptr == cnt && !fillBuffer()) {
				bs[i++] = '\r';
				break;
			}

			if(buf[ptr] == '\n') {
				bs[i++] = '\n';
				ptr++;
			} else
				bs[i++] = '\r';
		}

		return i == off ? -1 : i - off;
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
			passAsIs = isBinary;
			detectBinary = false;
			if(isBinary && abortIfBinary) {
				throw new IsBinaryException();
			}
			if(!passAsIs && forCheckout) {
				passAsIs = RawText.isCrLfText(buf, cnt, cnt < buf.length);
			}
		}
		ptr = 0;
		return true;
	}
}
