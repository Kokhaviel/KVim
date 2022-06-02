/*
 * Copyright (C) 2014, 2021 Andrey Loskutov <loskutov@gmx.de> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.ignore;

import org.eclipse.jgit.errors.InvalidPatternException;
import org.eclipse.jgit.ignore.internal.PathMatcher;

import static org.eclipse.jgit.ignore.IMatcher.NO_MATCH;
import static org.eclipse.jgit.ignore.internal.Strings.*;

public class FastIgnoreRule {

	public static final char PATH_SEPARATOR = '/';
	private IMatcher matcher;
	private boolean inverse;
	private boolean dirOnly;

	FastIgnoreRule() {
		matcher = IMatcher.NO_MATCH;
	}

	void parse(String pattern) throws InvalidPatternException {
		if (pattern == null) {
			throw new IllegalArgumentException("Pattern must not be null!");
		}
		if (pattern.length() == 0) {
			dirOnly = false;
			inverse = false;
			this.matcher = NO_MATCH;
			return;
		}
		inverse = pattern.charAt(0) == '!';
		if (inverse) {
			pattern = pattern.substring(1);
			if (pattern.length() == 0) {
				dirOnly = false;
				this.matcher = NO_MATCH;
				return;
			}
		}
		if (pattern.charAt(0) == '#') {
			this.matcher = NO_MATCH;
			dirOnly = false;
			return;
		}
		if (pattern.charAt(0) == '\\' && pattern.length() > 1) {
			char next = pattern.charAt(1);
			if (next == '!' || next == '#') {
				pattern = pattern.substring(1);
			}
		}
		dirOnly = isDirectoryPattern(pattern);
		if (dirOnly) {
			pattern = stripTrailingWhitespace(pattern);
			pattern = stripTrailing(pattern, PATH_SEPARATOR);
			if (pattern.length() == 0) {
				this.matcher = NO_MATCH;
				return;
			}
		}
		this.matcher = PathMatcher.createPathMatcher(pattern,
				PATH_SEPARATOR, dirOnly);
	}

	public boolean isMatch(String path, boolean directory, boolean pathMatch) {
		if (path == null)
			return false;
		if (path.length() == 0)
			return false;
		return matcher.matches(path, directory, pathMatch);
	}

	public boolean getResult() {
		return !inverse;
	}

	public boolean isEmpty() {
		return matcher == NO_MATCH;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		if (inverse)
			sb.append('!');
		sb.append(matcher);
		if (dirOnly)
			sb.append(PATH_SEPARATOR);
		return sb.toString();

	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + (inverse ? 1231 : 1237);
		result = prime * result + (dirOnly ? 1231 : 1237);
		result = prime * result + ((matcher == null) ? 0 : matcher.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (!(obj instanceof FastIgnoreRule))
			return false;

		FastIgnoreRule other = (FastIgnoreRule) obj;
		if (inverse != other.inverse)
			return false;
		if (dirOnly != other.dirOnly)
			return false;
		return matcher.equals(other.matcher);
	}
}
