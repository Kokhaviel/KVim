/*
 * Copyright (C) 2008-2009, Google Inc.
 * Copyright (C) 2008, Marek Zawirski <marek.zawirski@gmail.com>
 * Copyright (C) 2007-2009, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2006-2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.lib;

import java.text.MessageFormat;

import org.eclipse.jgit.errors.InvalidObjectIdException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.util.NB;
import org.eclipse.jgit.util.RawParseUtils;

public class MutableObjectId extends AnyObjectId {

	public MutableObjectId() {
		super();
	}

	public void setByte(int index, int value) {
		switch(index >> 2) {
			case 0:
				w1 = set(w1, index & 3, value);
				break;
			case 1:
				w2 = set(w2, index & 3, value);
				break;
			case 2:
				w3 = set(w3, index & 3, value);
				break;
			case 3:
				w4 = set(w4, index & 3, value);
				break;
			case 4:
				w5 = set(w5, index & 3, value);
				break;
			default:
				throw new ArrayIndexOutOfBoundsException(index);
		}
	}

	private static int set(int w, int index, int value) {
		value &= 0xff;

		switch(index) {
			case 0:
				return (w & 0x00ffffff) | (value << 24);
			case 1:
				return (w & 0xff00ffff) | (value << 16);
			case 2:
				return (w & 0xffff00ff) | (value << 8);
			case 3:
				return (w & 0xffffff00) | value;
			default:
				throw new ArrayIndexOutOfBoundsException();
		}
	}

	public void clear() {
		w1 = 0;
		w2 = 0;
		w3 = 0;
		w4 = 0;
		w5 = 0;
	}

	public void fromObjectId(AnyObjectId src) {
		this.w1 = src.w1;
		this.w2 = src.w2;
		this.w3 = src.w3;
		this.w4 = src.w4;
		this.w5 = src.w5;
	}

	public void fromRaw(byte[] bs) {
		fromRaw(bs, 0);
	}

	public void fromRaw(byte[] bs, int p) {
		w1 = NB.decodeInt32(bs, p);
		w2 = NB.decodeInt32(bs, p + 4);
		w3 = NB.decodeInt32(bs, p + 8);
		w4 = NB.decodeInt32(bs, p + 12);
		w5 = NB.decodeInt32(bs, p + 16);
	}

	public void fromRaw(int[] ints) {
		fromRaw(ints, 0);
	}

	public void fromRaw(int[] ints, int p) {
		w1 = ints[p];
		w2 = ints[p + 1];
		w3 = ints[p + 2];
		w4 = ints[p + 3];
		w5 = ints[p + 4];
	}

	public void set(int a, int b, int c, int d, int e) {
		w1 = a;
		w2 = b;
		w3 = c;
		w4 = d;
		w5 = e;
	}

	public void fromString(byte[] buf, int offset) {
		fromHexString(buf, offset);
	}

	public void fromString(String str) {
		if(str.length() != Constants.OBJECT_ID_STRING_LENGTH)
			throw new IllegalArgumentException(MessageFormat.format(
					JGitText.get().invalidId, str));
		fromHexString(Constants.encodeASCII(str), 0);
	}

	private void fromHexString(byte[] bs, int p) {
		try {
			w1 = RawParseUtils.parseHexInt32(bs, p);
			w2 = RawParseUtils.parseHexInt32(bs, p + 8);
			w3 = RawParseUtils.parseHexInt32(bs, p + 16);
			w4 = RawParseUtils.parseHexInt32(bs, p + 24);
			w5 = RawParseUtils.parseHexInt32(bs, p + 32);
		} catch(ArrayIndexOutOfBoundsException e) {
			InvalidObjectIdException e1 = new InvalidObjectIdException(bs, p,
					Constants.OBJECT_ID_STRING_LENGTH);
			e1.initCause(e);
			throw e1;
		}
	}

	@Override
	public ObjectId toObjectId() {
		return new ObjectId(this);
	}
}
