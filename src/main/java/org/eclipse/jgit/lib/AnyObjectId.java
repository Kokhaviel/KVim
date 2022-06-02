/*
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
import java.io.OutputStream;
import java.io.Writer;
import java.nio.ByteBuffer;

import org.eclipse.jgit.util.NB;
import org.eclipse.jgit.util.References;

public abstract class AnyObjectId implements Comparable<AnyObjectId> {

	@Deprecated
	@SuppressWarnings("AmbiguousMethodReference")
	public static boolean equals(final AnyObjectId firstObjectId,
								 final AnyObjectId secondObjectId) {
		return isEqual(firstObjectId, secondObjectId);
	}

	public static boolean isEqual(final AnyObjectId firstObjectId,
								  final AnyObjectId secondObjectId) {
		if(References.isSameObject(firstObjectId, secondObjectId)) {
			return true;
		}

		return firstObjectId.w3 == secondObjectId.w3
				&& firstObjectId.w4 == secondObjectId.w4
				&& firstObjectId.w5 == secondObjectId.w5
				&& firstObjectId.w1 == secondObjectId.w1
				&& firstObjectId.w2 == secondObjectId.w2;
	}

	int w1;
	int w2;
	int w3;
	int w4;
	int w5;

	public final int getFirstByte() {
		return w1 >>> 24;
	}

	public final int getByte(int index) {
		int w;
		switch(index >> 2) {
			case 0:
				w = w1;
				break;
			case 1:
				w = w2;
				break;
			case 2:
				w = w3;
				break;
			case 3:
				w = w4;
				break;
			case 4:
				w = w5;
				break;
			default:
				throw new ArrayIndexOutOfBoundsException(index);
		}

		return (w >>> (8 * (3 - (index & 3)))) & 0xff;
	}

	@Override
	public final int compareTo(AnyObjectId other) {
		if(this == other)
			return 0;

		int cmp;

		cmp = NB.compareUInt32(w1, other.w1);
		if(cmp != 0)
			return cmp;

		cmp = NB.compareUInt32(w2, other.w2);
		if(cmp != 0)
			return cmp;

		cmp = NB.compareUInt32(w3, other.w3);
		if(cmp != 0)
			return cmp;

		cmp = NB.compareUInt32(w4, other.w4);
		if(cmp != 0)
			return cmp;

		return NB.compareUInt32(w5, other.w5);
	}

	public final int compareTo(byte[] bs, int p) {
		int cmp;

		cmp = NB.compareUInt32(w1, NB.decodeInt32(bs, p));
		if(cmp != 0)
			return cmp;

		cmp = NB.compareUInt32(w2, NB.decodeInt32(bs, p + 4));
		if(cmp != 0)
			return cmp;

		cmp = NB.compareUInt32(w3, NB.decodeInt32(bs, p + 8));
		if(cmp != 0)
			return cmp;

		cmp = NB.compareUInt32(w4, NB.decodeInt32(bs, p + 12));
		if(cmp != 0)
			return cmp;

		return NB.compareUInt32(w5, NB.decodeInt32(bs, p + 16));
	}

	public final int compareTo(int[] bs, int p) {
		int cmp;

		cmp = NB.compareUInt32(w1, bs[p]);
		if(cmp != 0)
			return cmp;

		cmp = NB.compareUInt32(w2, bs[p + 1]);
		if(cmp != 0)
			return cmp;

		cmp = NB.compareUInt32(w3, bs[p + 2]);
		if(cmp != 0)
			return cmp;

		cmp = NB.compareUInt32(w4, bs[p + 3]);
		if(cmp != 0)
			return cmp;

		return NB.compareUInt32(w5, bs[p + 4]);
	}

	public boolean startsWith(AbbreviatedObjectId abbr) {
		return abbr.prefixCompare(this) == 0;
	}

	@Override
	public final int hashCode() {
		return w2;
	}

	@SuppressWarnings({"NonOverridingEquals", "AmbiguousMethodReference"})
	public final boolean equals(AnyObjectId other) {
		return other != null && isEqual(this, other);
	}

	@Override
	public final boolean equals(Object o) {
		if(o instanceof AnyObjectId) {
			return equals((AnyObjectId) o);
		}
		return false;
	}

	public void copyRawTo(ByteBuffer w) {
		w.putInt(w1);
		w.putInt(w2);
		w.putInt(w3);
		w.putInt(w4);
		w.putInt(w5);
	}

	public void copyRawTo(byte[] b, int o) {
		NB.encodeInt32(b, o, w1);
		NB.encodeInt32(b, o + 4, w2);
		NB.encodeInt32(b, o + 8, w3);
		NB.encodeInt32(b, o + 12, w4);
		NB.encodeInt32(b, o + 16, w5);
	}

	public void copyRawTo(int[] b, int o) {
		b[o] = w1;
		b[o + 1] = w2;
		b[o + 2] = w3;
		b[o + 3] = w4;
		b[o + 4] = w5;
	}

	public void copyRawTo(OutputStream w) throws IOException {
		writeRawInt(w, w1);
		writeRawInt(w, w2);
		writeRawInt(w, w3);
		writeRawInt(w, w4);
		writeRawInt(w, w5);
	}

	private static void writeRawInt(OutputStream w, int v)
			throws IOException {
		w.write(v >>> 24);
		w.write(v >>> 16);
		w.write(v >>> 8);
		w.write(v);
	}

	public void copyTo(OutputStream w) throws IOException {
		w.write(toHexByteArray());
	}

	public void copyTo(byte[] b, int o) {
		formatHexByte(b, o, w1);
		formatHexByte(b, o + 8, w2);
		formatHexByte(b, o + 16, w3);
		formatHexByte(b, o + 24, w4);
		formatHexByte(b, o + 32, w5);
	}

	public void copyTo(ByteBuffer b) {
		b.put(toHexByteArray());
	}

	private byte[] toHexByteArray() {
		final byte[] dst = new byte[Constants.OBJECT_ID_STRING_LENGTH];
		formatHexByte(dst, 0, w1);
		formatHexByte(dst, 8, w2);
		formatHexByte(dst, 16, w3);
		formatHexByte(dst, 24, w4);
		formatHexByte(dst, 32, w5);
		return dst;
	}

	private static final byte[] hexbyte = {'0', '1', '2', '3', '4', '5', '6',
			'7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};

	private static void formatHexByte(byte[] dst, int p, int w) {
		int o = p + 7;
		while(o >= p && w != 0) {
			dst[o--] = hexbyte[w & 0xf];
			w >>>= 4;
		}
		while(o >= p)
			dst[o--] = '0';
	}

	public void copyTo(Writer w) throws IOException {
		w.write(toHexCharArray());
	}

	public void copyTo(char[] tmp, Writer w) throws IOException {
		toHexCharArray(tmp);
		w.write(tmp, 0, Constants.OBJECT_ID_STRING_LENGTH);
	}

	public void copyTo(char[] tmp, StringBuilder w) {
		toHexCharArray(tmp);
		w.append(tmp, 0, Constants.OBJECT_ID_STRING_LENGTH);
	}

	private char[] toHexCharArray() {
		final char[] dst = new char[Constants.OBJECT_ID_STRING_LENGTH];
		toHexCharArray(dst);
		return dst;
	}

	private void toHexCharArray(char[] dst) {
		formatHexChar(dst, 0, w1);
		formatHexChar(dst, 8, w2);
		formatHexChar(dst, 16, w3);
		formatHexChar(dst, 24, w4);
		formatHexChar(dst, 32, w5);
	}

	private static final char[] hexchar = {'0', '1', '2', '3', '4', '5', '6',
			'7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};

	static void formatHexChar(char[] dst, int p, int w) {
		int o = p + 7;
		while(o >= p && w != 0) {
			dst[o--] = hexchar[w & 0xf];
			w >>>= 4;
		}
		while(o >= p)
			dst[o--] = '0';
	}

	@Override
	public String toString() {
		return "AnyObjectId[" + name() + "]";
	}

	public final String name() {
		return new String(toHexCharArray());
	}

	public final String getName() {
		return name();
	}

	public AbbreviatedObjectId abbreviate(int len) {
		final int a = AbbreviatedObjectId.mask(len, 1, w1);
		final int b = AbbreviatedObjectId.mask(len, 2, w2);
		final int c = AbbreviatedObjectId.mask(len, 3, w3);
		final int d = AbbreviatedObjectId.mask(len, 4, w4);
		final int e = AbbreviatedObjectId.mask(len, 5, w5);
		return new AbbreviatedObjectId(len, a, b, c, d, e);
	}

	public final ObjectId copy() {
		if(getClass() == ObjectId.class)
			return (ObjectId) this;
		return new ObjectId(this);
	}

	public abstract ObjectId toObjectId();
}
