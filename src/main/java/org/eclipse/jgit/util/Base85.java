/*
 * Copyright (C) 2021 Thomas Wolf <thomas.wolf@paranor.ch> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.util;

import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Arrays;

import org.eclipse.jgit.internal.JGitText;

public final class Base85 {

	private static final byte[] ENCODE =
			("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz!#$%&()*+-;<=>?@^_`{|}~").getBytes(StandardCharsets.US_ASCII);

	private static final int[] DECODE = new int[256];

	static {
		Arrays.fill(DECODE, -1);
		for(int i = 0; i < ENCODE.length; i++) {
			DECODE[ENCODE[i]] = i;
		}
	}

	private Base85() {
	}

	public static int encodedLength(int rawLength) {
		return (rawLength + 3) / 4 * 5;
	}

	public static byte[] encode(byte[] data) {
		return encode(data, 0, data.length);
	}

	public static byte[] encode(byte[] data, int start, int length) {
		byte[] result = new byte[encodedLength(length)];
		int end = start + length;
		int in = start;
		int out = 0;
		while(in < end) {
			long accumulator = ((long) (data[in++] & 0xFF)) << 24;
			if(in < end) {
				accumulator |= (data[in++] & 0xFF) << 16;
				if(in < end) {
					accumulator |= (data[in++] & 0xFF) << 8;
					if(in < end) {
						accumulator |= (data[in++] & 0xFF);
					}
				}
			}
			for(int i = 4; i >= 0; i--) {
				result[out + i] = ENCODE[(int) (accumulator % 85)];
				accumulator /= 85;
			}
			out += 5;
		}
		return result;
	}

	public static byte[] decode(byte[] encoded, int expectedSize) {
		return decode(encoded, 0, encoded.length, expectedSize);
	}

	public static byte[] decode(byte[] encoded, int start, int length,
								int expectedSize) {
		if(length % 5 != 0) {
			throw new IllegalArgumentException(JGitText.get().base85length);
		}
		byte[] result = new byte[expectedSize];
		int end = start + length;
		int in = start;
		int out = 0;
		while(in < end && out < expectedSize) {
			long accumulator = 0;
			for(int i = 4; i >= 0; i--) {
				int val = DECODE[encoded[in++] & 0xFF];
				if(val < 0) {
					throw new IllegalArgumentException(MessageFormat.format(
							JGitText.get().base85invalidChar,
							Integer.toHexString(encoded[in - 1] & 0xFF)));
				}
				accumulator = accumulator * 85 + val;
			}
			if(accumulator > 0xFFFF_FFFFL) {
				throw new IllegalArgumentException(
						MessageFormat.format(JGitText.get().base85overflow,
								Long.toHexString(accumulator)));
			}
			result[out++] = (byte) (accumulator >>> 24);
			if(out < expectedSize) {
				result[out++] = (byte) (accumulator >>> 16);
				if(out < expectedSize) {
					result[out++] = (byte) (accumulator >>> 8);
					if(out < expectedSize) {
						result[out++] = (byte) accumulator;
					}
				}
			}
		}
		if(in < end) {
			throw new IllegalArgumentException(
					MessageFormat.format(JGitText.get().base85tooLong,
							expectedSize));
		}
		if(out < expectedSize) {
			throw new IllegalArgumentException(
					MessageFormat.format(JGitText.get().base85tooShort,
							expectedSize));
		}
		return result;
	}
}
