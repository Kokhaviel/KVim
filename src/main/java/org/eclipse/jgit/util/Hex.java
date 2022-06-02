/*
 * Copyright (C) 2020, Michael Dardis. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.util;

import java.text.MessageFormat;

import org.eclipse.jgit.internal.JGitText;

public final class Hex {

	private static final char[] HEX = "0123456789abcdef".toCharArray();

	private Hex() {
	}

	public static byte[] decode(String s) {
		int len = s.length();
		byte[] b = new byte[len / 2];

		for(int i = 0; i < len; i += 2) {
			int left = Character.digit(s.charAt(i), 16);
			int right = Character.digit(s.charAt(i + 1), 16);

			if(left == -1 || right == -1) {
				throw new IllegalArgumentException(MessageFormat.format(
						JGitText.get().invalidHexString,
						s));
			}

			b[i / 2] = (byte) (left << 4 | right);
		}
		return b;
	}

	public static String toHexString(byte[] b) {
		char[] c = new char[b.length * 2];

		for(int i = 0; i < b.length; i++) {
			int v = b[i] & 0xFF;

			c[i * 2] = HEX[v >>> 4];
			c[i * 2 + 1] = HEX[v & 0x0F];
		}

		return new String(c);
	}
}
