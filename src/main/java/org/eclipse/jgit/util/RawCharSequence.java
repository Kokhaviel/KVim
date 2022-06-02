/*
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.util;

public final class RawCharSequence implements CharSequence {
	public static final RawCharSequence EMPTY = new RawCharSequence(null, 0, 0);
	final byte[] buffer;
	final int startPtr;
	final int endPtr;

	public RawCharSequence(byte[] buf, int start, int end) {
		buffer = buf;
		startPtr = start;
		endPtr = end;
	}

	@Override
	public char charAt(int index) {
		return (char) (buffer[startPtr + index] & 0xff);
	}

	@Override
	public int length() {
		return endPtr - startPtr;
	}

	@Override
	public CharSequence subSequence(int start, int end) {
		return new RawCharSequence(buffer, startPtr + start, startPtr + end);
	}

	@Override
	public String toString() {
		final int n = length();
		final StringBuilder b = new StringBuilder(n);
		for(int i = 0; i < n; i++)
			b.append(charAt(i));
		return b.toString();
	}
}