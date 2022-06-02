/*
 * Copyright (C) 2014, Andrey Loskutov <loskutov@gmx.de> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.ignore.internal;

public class TrailingAsteriskMatcher extends NameMatcher {

	TrailingAsteriskMatcher(String pattern, Character pathSeparator, boolean dirOnly) {
		super(pattern, pathSeparator, dirOnly, true);

		if (subPattern.charAt(subPattern.length() - 1) != '*')
			throw new IllegalArgumentException(
					"Pattern must have trailing asterisk: " + pattern);
	}

	@Override
	public boolean matches(String segment, int startIncl, int endExcl) {
		String s = subPattern;
		int subLenth = s.length() - 1;
		if (subLenth == 0)
			return true;

		if (subLenth > (endExcl - startIncl))
			return false;

		for (int i = 0; i < subLenth; i++) {
			char c1 = s.charAt(i);
			char c2 = segment.charAt(i + startIncl);
			if (c1 != c2)
				return false;
		}
		return true;
	}

}
