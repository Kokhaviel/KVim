/*
 * Copyright (C) 2008-2009, Google Inc.
 * Copyright (C) 2009, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2006-2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.util;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.util.io.SilentFileInputStream;

import java.io.*;
import java.nio.ByteBuffer;
import java.text.MessageFormat;

public class IO {

	public static byte[] readFully(File path)
			throws IOException {
		return IO.readFully(path, Integer.MAX_VALUE);
	}

	public static byte[] readSome(File path, int limit)
			throws IOException {
		try(SilentFileInputStream in = new SilentFileInputStream(path)) {
			byte[] buf = new byte[limit];
			int cnt = 0;
			for(; ; ) {
				int n = in.read(buf, cnt, buf.length - cnt);
				if(n <= 0)
					break;
				cnt += n;
			}
			if(cnt == buf.length)
				return buf;
			byte[] res = new byte[cnt];
			System.arraycopy(buf, 0, res, 0, cnt);
			return res;
		}
	}

	public static byte[] readFully(File path, int max)
			throws IOException {
		try(SilentFileInputStream in = new SilentFileInputStream(path)) {
			long sz = Math.max(path.length(), 1);
			if(sz > max)
				throw new IOException(MessageFormat.format(
						JGitText.get().fileIsTooLarge, path));

			byte[] buf = new byte[(int) sz];
			int valid = 0;
			for(; ; ) {
				if(buf.length == valid) {
					if(buf.length == max) {
						int next = in.read();
						if(next < 0)
							break;

						throw new IOException(MessageFormat.format(
								JGitText.get().fileIsTooLarge, path));
					}

					byte[] nb = new byte[Math.min(buf.length * 2, max)];
					System.arraycopy(buf, 0, nb, 0, valid);
					buf = nb;
				}
				int n = in.read(buf, valid, buf.length - valid);
				if(n < 0)
					break;
				valid += n;
			}
			if(valid < buf.length) {
				byte[] nb = new byte[valid];
				System.arraycopy(buf, 0, nb, 0, valid);
				buf = nb;
			}
			return buf;
		}
	}

	public static ByteBuffer readWholeStream(InputStream in, int sizeHint)
			throws IOException {
		byte[] out = new byte[sizeHint];
		int pos = 0;
		while(pos < out.length) {
			int read = in.read(out, pos, out.length - pos);
			if(read < 0)
				return ByteBuffer.wrap(out, 0, pos);
			pos += read;
		}

		int last = in.read();
		if(last < 0)
			return ByteBuffer.wrap(out, 0, pos);

		try(TemporaryBuffer.Heap tmp = new TemporaryBuffer.Heap(
				Integer.MAX_VALUE)) {
			tmp.write(out);
			tmp.write(last);
			tmp.copy(in);
			return ByteBuffer.wrap(tmp.toByteArray());
		}
	}

	public static void readFully(final InputStream fd, final byte[] dst,
								 int off, int len) throws IOException {
		while(len > 0) {
			final int r = fd.read(dst, off, len);
			if(r <= 0)
				throw new EOFException(JGitText.get().shortReadOfBlock);
			off += r;
			len -= r;
		}
	}

	public static int readFully(InputStream fd, byte[] dst, int off)
			throws IOException {
		int r;
		int len = 0;
		while(off < dst.length
				&& (r = fd.read(dst, off, dst.length - off)) >= 0) {
			off += r;
			len += r;
		}
		return len;
	}

	public static void skipFully(InputStream fd, long toSkip)
			throws IOException {
		while(toSkip > 0) {
			final long r = fd.skip(toSkip);
			if(r <= 0)
				throw new EOFException(JGitText.get().shortSkipOfBlock);
			toSkip -= r;
		}
	}

	public static String readLine(Reader in, int sizeHint) throws IOException {
		if(in.markSupported()) {
			if(sizeHint <= 0) {
				sizeHint = 1024;
			}
			StringBuilder sb = new StringBuilder(sizeHint);
			char[] buf = new char[sizeHint];
			while(true) {
				in.mark(sizeHint);
				int n = in.read(buf);
				if(n < 0) {
					in.reset();
					return sb.toString();
				}
				for(int i = 0; i < n; i++) {
					if(buf[i] == '\n') {
						resetAndSkipFully(in, ++i);
						sb.append(buf, 0, i);
						return sb.toString();
					}
				}
				if(n > 0) {
					sb.append(buf, 0, n);
				}
				resetAndSkipFully(in, n);
			}
		}
		StringBuilder buf = sizeHint > 0 ? new StringBuilder(sizeHint)
				: new StringBuilder();
		int i;
		while((i = in.read()) != -1) {
			char c = (char) i;
			buf.append(c);
			if(c == '\n') {
				break;
			}
		}
		return buf.toString();
	}

	private static void resetAndSkipFully(Reader fd, long toSkip) throws IOException {
		fd.reset();
		while(toSkip > 0) {
			long r = fd.skip(toSkip);
			if(r <= 0) {
				throw new EOFException(JGitText.get().shortSkipOfBlock);
			}
			toSkip -= r;
		}
	}

	private IO() {
	}
}
