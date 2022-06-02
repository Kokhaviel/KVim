/*
 * Copyright (C) 2008, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2006-2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.lib;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.errors.InvalidObjectIdException;
import org.eclipse.jgit.util.NB;
import org.eclipse.jgit.util.RawParseUtils;

public class ObjectId extends AnyObjectId implements Serializable {
	private static final long serialVersionUID = 1L;

	private static final ObjectId ZEROID;

	private static final String ZEROID_STR;

	static {
		ZEROID = new ObjectId(0, 0, 0, 0, 0);
		ZEROID_STR = ZEROID.name();
	}

	public static ObjectId zeroId() {
		return ZEROID;
	}

	public static boolean isId(@Nullable String id) {
		if(id == null) {
			return false;
		}
		if(id.length() != Constants.OBJECT_ID_STRING_LENGTH)
			return false;
		try {
			for(int i = 0; i < Constants.OBJECT_ID_STRING_LENGTH; i++) {
				RawParseUtils.parseHexInt4((byte) id.charAt(i));
			}
			return true;
		} catch(ArrayIndexOutOfBoundsException e) {
			return false;
		}
	}

	public static String toString(ObjectId i) {
		return i != null ? i.name() : ZEROID_STR;
	}

	public static boolean equals(final byte[] firstBuffer, final int fi,
								 final byte[] secondBuffer, final int si) {
		return firstBuffer[fi] == secondBuffer[si]
				&& firstBuffer[fi + 1] == secondBuffer[si + 1]
				&& firstBuffer[fi + 2] == secondBuffer[si + 2]
				&& firstBuffer[fi + 3] == secondBuffer[si + 3]
				&& firstBuffer[fi + 4] == secondBuffer[si + 4]
				&& firstBuffer[fi + 5] == secondBuffer[si + 5]
				&& firstBuffer[fi + 6] == secondBuffer[si + 6]
				&& firstBuffer[fi + 7] == secondBuffer[si + 7]
				&& firstBuffer[fi + 8] == secondBuffer[si + 8]
				&& firstBuffer[fi + 9] == secondBuffer[si + 9]
				&& firstBuffer[fi + 10] == secondBuffer[si + 10]
				&& firstBuffer[fi + 11] == secondBuffer[si + 11]
				&& firstBuffer[fi + 12] == secondBuffer[si + 12]
				&& firstBuffer[fi + 13] == secondBuffer[si + 13]
				&& firstBuffer[fi + 14] == secondBuffer[si + 14]
				&& firstBuffer[fi + 15] == secondBuffer[si + 15]
				&& firstBuffer[fi + 16] == secondBuffer[si + 16]
				&& firstBuffer[fi + 17] == secondBuffer[si + 17]
				&& firstBuffer[fi + 18] == secondBuffer[si + 18]
				&& firstBuffer[fi + 19] == secondBuffer[si + 19];
	}

	public static ObjectId fromRaw(byte[] bs) {
		return fromRaw(bs, 0);
	}

	public static ObjectId fromRaw(byte[] bs, int p) {
		final int a = NB.decodeInt32(bs, p);
		final int b = NB.decodeInt32(bs, p + 4);
		final int c = NB.decodeInt32(bs, p + 8);
		final int d = NB.decodeInt32(bs, p + 12);
		final int e = NB.decodeInt32(bs, p + 16);
		return new ObjectId(a, b, c, d, e);
	}

	public static ObjectId fromRaw(int[] is) {
		return fromRaw(is, 0);
	}

	public static ObjectId fromRaw(int[] is, int p) {
		return new ObjectId(is[p], is[p + 1], is[p + 2], is[p + 3], is[p + 4]);
	}

	public static ObjectId fromString(byte[] buf, int offset) {
		return fromHexString(buf, offset);
	}

	public static ObjectId fromString(String str) {
		if(str.length() != Constants.OBJECT_ID_STRING_LENGTH) {
			throw new InvalidObjectIdException(str);
		}
		return fromHexString(Constants.encodeASCII(str), 0);
	}

	private static ObjectId fromHexString(byte[] bs, int p) {
		try {
			final int a = RawParseUtils.parseHexInt32(bs, p);
			final int b = RawParseUtils.parseHexInt32(bs, p + 8);
			final int c = RawParseUtils.parseHexInt32(bs, p + 16);
			final int d = RawParseUtils.parseHexInt32(bs, p + 24);
			final int e = RawParseUtils.parseHexInt32(bs, p + 32);
			return new ObjectId(a, b, c, d, e);
		} catch(ArrayIndexOutOfBoundsException e) {
			InvalidObjectIdException e1 = new InvalidObjectIdException(bs, p,
					Constants.OBJECT_ID_STRING_LENGTH);
			e1.initCause(e);
			throw e1;
		}
	}

	public ObjectId(int new_1, int new_2, int new_3, int new_4, int new_5) {
		w1 = new_1;
		w2 = new_2;
		w3 = new_3;
		w4 = new_4;
		w5 = new_5;
	}

	protected ObjectId(AnyObjectId src) {
		w1 = src.w1;
		w2 = src.w2;
		w3 = src.w3;
		w4 = src.w4;
		w5 = src.w5;
	}

	@Override
	public ObjectId toObjectId() {
		return this;
	}

	private void writeObject(ObjectOutputStream os) throws IOException {
		os.writeInt(w1);
		os.writeInt(w2);
		os.writeInt(w3);
		os.writeInt(w4);
		os.writeInt(w5);
	}

	private void readObject(ObjectInputStream ois) throws IOException {
		w1 = ois.readInt();
		w2 = ois.readInt();
		w3 = ois.readInt();
		w4 = ois.readInt();
		w5 = ois.readInt();
	}
}
