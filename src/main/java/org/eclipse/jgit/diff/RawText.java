/*
 * Copyright (C) 2009, Google Inc.
 * Copyright (C) 2008-2021, Johannes E. Schindelin <johannes.schindelin@gmx.de> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.diff;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jgit.errors.BinaryBlobException;
import org.eclipse.jgit.errors.LargeObjectException;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.util.IO;
import org.eclipse.jgit.util.IntList;
import org.eclipse.jgit.util.RawParseUtils;

public class RawText extends Sequence {

	public static final RawText EMPTY_TEXT = new RawText(new byte[0]);
	private static final int FIRST_FEW_BYTES = 8 * 1024;
	private static final AtomicInteger BUFFER_SIZE = new AtomicInteger(FIRST_FEW_BYTES);
	protected final byte[] content;
	protected final IntList lines;

	public RawText(byte[] input) {
		this(input, RawParseUtils.lineMap(input, 0, input.length));
	}

	public RawText(byte[] input, IntList lineMap) {
		content = input;
		lines = lineMap;
	}

	public RawText(File file) throws IOException {
		this(IO.readFully(file));
	}

	@Override
	public int size() {
		return lines.size() - 2;
	}

	public void writeLine(OutputStream out, int i)
			throws IOException {
		int start = getStart(i);
		int end = getEnd(i);
		if(content[end - 1] == '\n')
			end--;
		out.write(content, start, end - start);
	}

	public boolean isMissingNewlineAtEnd() {
		final int end = lines.get(lines.size() - 1);
		if(end == 0)
			return true;
		return content[end - 1] != '\n';
	}

	public String getString(int i) {
		return getString(i, i + 1, true);
	}

	public ByteBuffer getRawString(int i) {
		int s = getStart(i);
		int e = getEnd(i);
		if(e > 0 && content[e - 1] == '\n') {
			e--;
		}
		return ByteBuffer.wrap(content, s, e - s);
	}

	public String getString(int begin, int end, boolean dropLF) {
		if(begin == end)
			return "";

		int s = getStart(begin);
		int e = getEnd(end - 1);
		if(dropLF && content[e - 1] == '\n')
			e--;
		return decode(s, e);
	}

	protected String decode(int start, int end) {
		return RawParseUtils.decode(content, start, end);
	}

	private int getStart(int i) {
		return lines.get(i + 1);
	}

	private int getEnd(int i) {
		return lines.get(i + 2);
	}

	public static int getBufferSize() {
		return BUFFER_SIZE.get();
	}

	public static boolean isBinary(InputStream raw) throws IOException {
		final byte[] buffer = new byte[getBufferSize()];
		int cnt = 0;
		while(cnt < buffer.length) {
			final int n = raw.read(buffer, cnt, buffer.length - cnt);
			if(n == -1)
				break;
			cnt += n;
		}
		return isBinary(buffer, cnt, cnt < buffer.length);
	}

	public static boolean isBinary(byte[] raw, int length, boolean complete) {
		int maxLength = getBufferSize();
		if(length > maxLength) {
			length = maxLength;
		}
		byte last = 'x';
		for(int ptr = 0; ptr < length; ptr++) {
			byte curr = raw[ptr];
			if(isBinary(curr, last)) {
				return true;
			}
			last = curr;
		}
		if(complete) {
			return last == '\r';
		}
		return false;
	}

	public static boolean isBinary(byte curr, byte prev) {
		return curr == '\0' || (curr != '\n' && prev == '\r') || prev == '\0';
	}

	public static boolean isCrLfText(InputStream raw) throws IOException {
		byte[] buffer = new byte[getBufferSize()];
		int cnt = 0;
		while(cnt < buffer.length) {
			int n = raw.read(buffer, cnt, buffer.length - cnt);
			if(n == -1) {
				break;
			}
			cnt += n;
		}
		return isCrLfText(buffer, cnt);
	}

	public static boolean isCrLfText(byte[] raw, int length) {
		return isCrLfText(raw, length, false);
	}

	public static boolean isCrLfText(byte[] raw, int length, boolean complete) {
		boolean has_crlf = false;
		byte last = 'x';
		for(int ptr = 0; ptr < length; ptr++) {
			byte curr = raw[ptr];
			if(isBinary(curr, last)) {
				return false;
			}
			if(curr == '\n' && last == '\r') {
				has_crlf = true;
			}
			last = curr;
		}
		if(last == '\r') {
			if(complete) {
				return false;
			}
		}
		return has_crlf;
	}

	public static RawText load(ObjectLoader ldr, int threshold)
			throws IOException, BinaryBlobException {
		long sz = ldr.getSize();

		if(sz > threshold) {
			throw new BinaryBlobException();
		}

		int bufferSize = getBufferSize();
		if(sz <= bufferSize) {
			byte[] data = ldr.getCachedBytes(bufferSize);
			if(isBinary(data, data.length, true)) {
				throw new BinaryBlobException();
			}
			return new RawText(data);
		}

		byte[] head = new byte[bufferSize];
		try(InputStream stream = ldr.openStream()) {
			int off = 0;
			int left = head.length;
			byte last = 'x';
			while(left > 0) {
				int n = stream.read(head, off, left);
				if(n < 0) {
					throw new EOFException();
				}
				left -= n;

				while(n > 0) {
					byte curr = head[off];
					if(isBinary(curr, last)) {
						throw new BinaryBlobException();
					}
					last = curr;
					off++;
					n--;
				}
			}

			byte[] data;
			try {
				data = new byte[(int) sz];
			} catch(OutOfMemoryError e) {
				throw new LargeObjectException.OutOfMemory(e);
			}

			System.arraycopy(head, 0, data, 0, head.length);
			IO.readFully(stream, data, off, (int) (sz - off));
			return new RawText(data, RawParseUtils.lineMapOrBinary(data, 0, (int) sz));
		}
	}
}
