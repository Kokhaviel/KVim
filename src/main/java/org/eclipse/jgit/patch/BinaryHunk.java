/*
 * Copyright (C) 2008, Google Inc.
 * Copyright (C) 2008, Robin Rosenberg <robin.rosenberg@dewire.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.patch;

import static org.eclipse.jgit.lib.Constants.encodeASCII;
import static org.eclipse.jgit.util.RawParseUtils.match;
import static org.eclipse.jgit.util.RawParseUtils.nextLF;
import static org.eclipse.jgit.util.RawParseUtils.parseBase10;

public class BinaryHunk {
	private static final byte[] LITERAL = encodeASCII("literal ");

	private static final byte[] DELTA = encodeASCII("delta ");

	public enum Type {
		LITERAL_DEFLATED,
		DELTA_DEFLATED
	}

	private final FileHeader file;

	final int startOffset;
	int endOffset;
	private Type type;
	private int length;

	BinaryHunk(FileHeader fh, int offset) {
		file = fh;
		startOffset = offset;
	}

	public byte[] getBuffer() {
		return file.buf;
	}

	public int getStartOffset() {
		return startOffset;
	}

	public int getEndOffset() {
		return endOffset;
	}

	public Type getType() {
		return type;
	}

	public int getSize() {
		return length;
	}

	int parseHunk(int ptr, int end) {
		final byte[] buf = file.buf;

		if(match(buf, ptr, LITERAL) >= 0) {
			type = Type.LITERAL_DEFLATED;
			length = parseBase10(buf, ptr + LITERAL.length, null);

		} else if(match(buf, ptr, DELTA) >= 0) {
			type = Type.DELTA_DEFLATED;
			length = parseBase10(buf, ptr + DELTA.length, null);

		} else {
			return -1;
		}
		ptr = nextLF(buf, ptr);

		while(ptr < end) {
			final boolean empty = buf[ptr] == '\n';
			ptr = nextLF(buf, ptr);
			if(empty)
				break;
		}

		return ptr;
	}
}
