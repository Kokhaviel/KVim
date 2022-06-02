/*
 * Copyright (C) 2009, Google Inc.
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.util;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.Constants;

public class RawSubStringPattern {
	private final String needleString;

	private final byte[] needle;

	public RawSubStringPattern(String patternText) {
		if (patternText.length() == 0)
			throw new IllegalArgumentException(JGitText.get().cannotMatchOnEmptyString);
		needleString = patternText;

		final byte[] b = Constants.encode(patternText);
		needle = new byte[b.length];
		for (int i = 0; i < b.length; i++)
			needle[i] = lc(b[i]);
	}

	public int match(RawCharSequence rcs) {
		final int needleLen = needle.length;

		final byte[] text = rcs.buffer;
		int matchPos = rcs.startPtr;
		final int maxPos = rcs.endPtr - needleLen;

		OUTER: for (; matchPos <= maxPos; matchPos++) {

			int si = matchPos + 1;
			for (int j = 1; j < needleLen; j++, si++) {
				if (neq(needle[j], text[si]))
					continue OUTER;
			}
			return matchPos;
		}
		return -1;
	}

	private static boolean neq(byte a, byte b) {
		return a != b && a != lc(b);
	}

	private static byte lc(byte q) {
		return (byte) StringUtils.toLowerCase((char) (q & 0xff));
	}

	public String pattern() {
		return needleString;
	}

	@Override
	public String toString() {
		return pattern();
	}
}
