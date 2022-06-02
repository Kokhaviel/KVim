/*
 * Copyright (C) 2008-2009, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.lib;

import java.io.Serializable;
import java.text.MessageFormat;

import org.eclipse.jgit.errors.InvalidObjectIdException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.util.NB;
import org.eclipse.jgit.util.RawParseUtils;

public final class AbbreviatedObjectId implements Serializable {
	private static final long serialVersionUID = 1L;

	public static boolean isId(String id) {
		if(id.length() < 2 || Constants.OBJECT_ID_STRING_LENGTH < id.length())
			return false;
		try {
			for(int i = 0; i < id.length(); i++)
				RawParseUtils.parseHexInt4((byte) id.charAt(i));
			return true;
		} catch(ArrayIndexOutOfBoundsException e) {
			return false;
		}
	}

	public static AbbreviatedObjectId fromString(final byte[] buf,
												 final int offset, final int end) {
		if(end - offset > Constants.OBJECT_ID_STRING_LENGTH)
			throw new IllegalArgumentException(MessageFormat.format(
					JGitText.get().invalidIdLength,
					end - offset,
					Constants.OBJECT_ID_STRING_LENGTH));
		return fromHexString(buf, offset, end);
	}

	public static AbbreviatedObjectId fromObjectId(AnyObjectId id) {
		return new AbbreviatedObjectId(Constants.OBJECT_ID_STRING_LENGTH,
				id.w1, id.w2, id.w3, id.w4, id.w5);
	}

	public static AbbreviatedObjectId fromString(String str) {
		if(str.length() > Constants.OBJECT_ID_STRING_LENGTH)
			throw new IllegalArgumentException(MessageFormat.format(JGitText.get().invalidId, str));
		final byte[] b = Constants.encodeASCII(str);
		return fromHexString(b, 0, b.length);
	}

	private static AbbreviatedObjectId fromHexString(final byte[] bs,
													 int ptr, final int end) {
		try {
			final int a = hexUInt32(bs, ptr, end);
			final int b = hexUInt32(bs, ptr + 8, end);
			final int c = hexUInt32(bs, ptr + 16, end);
			final int d = hexUInt32(bs, ptr + 24, end);
			final int e = hexUInt32(bs, ptr + 32, end);
			return new AbbreviatedObjectId(end - ptr, a, b, c, d, e);
		} catch(ArrayIndexOutOfBoundsException e) {
			InvalidObjectIdException e1 = new InvalidObjectIdException(bs, ptr,
					end - ptr);
			e1.initCause(e);
			throw e1;
		}
	}

	private static int hexUInt32(final byte[] bs, int p, final int end) {
		if(8 <= end - p)
			return RawParseUtils.parseHexInt32(bs, p);

		int r = 0, n = 0;
		while(n < 8 && p < end) {
			r <<= 4;
			r |= RawParseUtils.parseHexInt4(bs[p++]);
			n++;
		}
		return r << ((8 - n) * 4);
	}

	static int mask(int nibbles, int word, int v) {
		final int b = (word - 1) * 8;
		if(b + 8 <= nibbles) {
			return v;
		}

		if(nibbles <= b) {
			return 0;
		}

		final int s = 32 - (nibbles - b) * 4;
		return (v >>> s) << s;
	}

	final int nibbles;
	final int w1;
	final int w2;
	final int w3;
	final int w4;
	final int w5;

	AbbreviatedObjectId(final int n, final int new_1, final int new_2,
						final int new_3, final int new_4, final int new_5) {
		nibbles = n;
		w1 = new_1;
		w2 = new_2;
		w3 = new_3;
		w4 = new_4;
		w5 = new_5;
	}

	public int length() {
		return nibbles;
	}

	public boolean isComplete() {
		return length() == Constants.OBJECT_ID_STRING_LENGTH;
	}

	public ObjectId toObjectId() {
		return isComplete() ? new ObjectId(w1, w2, w3, w4, w5) : null;
	}

	public int prefixCompare(AnyObjectId other) {
		int cmp;

		cmp = NB.compareUInt32(w1, mask(1, other.w1));
		if(cmp != 0)
			return cmp;

		cmp = NB.compareUInt32(w2, mask(2, other.w2));
		if(cmp != 0)
			return cmp;

		cmp = NB.compareUInt32(w3, mask(3, other.w3));
		if(cmp != 0)
			return cmp;

		cmp = NB.compareUInt32(w4, mask(4, other.w4));
		if(cmp != 0)
			return cmp;

		return NB.compareUInt32(w5, mask(5, other.w5));
	}

	public int prefixCompare(byte[] bs, int p) {
		int cmp;

		cmp = NB.compareUInt32(w1, mask(1, NB.decodeInt32(bs, p)));
		if(cmp != 0)
			return cmp;

		cmp = NB.compareUInt32(w2, mask(2, NB.decodeInt32(bs, p + 4)));
		if(cmp != 0)
			return cmp;

		cmp = NB.compareUInt32(w3, mask(3, NB.decodeInt32(bs, p + 8)));
		if(cmp != 0)
			return cmp;

		cmp = NB.compareUInt32(w4, mask(4, NB.decodeInt32(bs, p + 12)));
		if(cmp != 0)
			return cmp;

		return NB.compareUInt32(w5, mask(5, NB.decodeInt32(bs, p + 16)));
	}

	public int prefixCompare(int[] bs, int p) {
		int cmp;

		cmp = NB.compareUInt32(w1, mask(1, bs[p]));
		if(cmp != 0)
			return cmp;

		cmp = NB.compareUInt32(w2, mask(2, bs[p + 1]));
		if(cmp != 0)
			return cmp;

		cmp = NB.compareUInt32(w3, mask(3, bs[p + 2]));
		if(cmp != 0)
			return cmp;

		cmp = NB.compareUInt32(w4, mask(4, bs[p + 3]));
		if(cmp != 0)
			return cmp;

		return NB.compareUInt32(w5, mask(5, bs[p + 4]));
	}

	public int getFirstByte() {
		return w1 >>> 24;
	}

	private int mask(int word, int v) {
		return mask(nibbles, word, v);
	}

	@Override
	public int hashCode() {
		return w1;
	}

	@Override
	public boolean equals(Object o) {
		if(o instanceof AbbreviatedObjectId) {
			final AbbreviatedObjectId b = (AbbreviatedObjectId) o;
			return nibbles == b.nibbles && w1 == b.w1 && w2 == b.w2
					&& w3 == b.w3 && w4 == b.w4 && w5 == b.w5;
		}
		return false;
	}

	public String name() {
		final char[] b = new char[Constants.OBJECT_ID_STRING_LENGTH];

		AnyObjectId.formatHexChar(b, 0, w1);
		if(nibbles <= 8)
			return new String(b, 0, nibbles);

		AnyObjectId.formatHexChar(b, 8, w2);
		if(nibbles <= 16)
			return new String(b, 0, nibbles);

		AnyObjectId.formatHexChar(b, 16, w3);
		if(nibbles <= 24)
			return new String(b, 0, nibbles);

		AnyObjectId.formatHexChar(b, 24, w4);
		if(nibbles <= 32)
			return new String(b, 0, nibbles);

		AnyObjectId.formatHexChar(b, 32, w5);
		return new String(b, 0, nibbles);
	}

	@Override
	public String toString() {
		return "AbbreviatedObjectId[" + name() + "]";
	}
}
