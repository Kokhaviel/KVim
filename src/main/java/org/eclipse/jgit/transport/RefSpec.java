/*
 * Copyright (C) 2008, 2013 Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import java.io.Serializable;
import java.text.MessageFormat;
import java.util.Objects;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;

public class RefSpec implements Serializable {
	private static final long serialVersionUID = 1L;

	public static boolean isWildcard(String s) {
		return s != null && s.contains("*");
	}

	private boolean force;
	private boolean wildcard;
	private boolean matching;
	private boolean negative;

	public enum WildcardMode {
		REQUIRE_MATCH,
		ALLOW_MISMATCH
	}

	private final WildcardMode allowMismatchedWildcards;
	private String srcName;
	private String dstName;

	public RefSpec() {
		matching = false;
		force = false;
		wildcard = false;
		srcName = Constants.HEAD;
		dstName = null;
		negative = false;
		allowMismatchedWildcards = WildcardMode.REQUIRE_MATCH;
	}

	public RefSpec(String spec, WildcardMode mode) {
		this.allowMismatchedWildcards = mode;
		String s = spec;

		if(s.startsWith("^+") || s.startsWith("+^")) {
			throw new IllegalArgumentException(
					JGitText.get().invalidNegativeAndForce);
		}

		if(s.startsWith("+")) {
			force = true;
			s = s.substring(1);
		}

		if(s.startsWith("^")) {
			negative = true;
			s = s.substring(1);
		}

		boolean matchPushSpec = false;
		final int c = s.lastIndexOf(':');
		if(c == 0) {
			s = s.substring(1);
			if(s.isEmpty()) {
				matchPushSpec = true;
				wildcard = true;
				srcName = Constants.R_HEADS + '*';
				dstName = srcName;
			} else {
				if(isWildcard(s)) {
					wildcard = true;
					if(mode == WildcardMode.REQUIRE_MATCH) {
						throw new IllegalArgumentException(MessageFormat
								.format(JGitText.get().invalidWildcards, spec));
					}
				}
				dstName = checkValid(s);
			}
		} else if(c > 0) {
			String src = s.substring(0, c);
			String dst = s.substring(c + 1);
			if(isWildcard(src) && isWildcard(dst)) {
				wildcard = true;
			} else if(isWildcard(src) || isWildcard(dst)) {
				wildcard = true;
				if(mode == WildcardMode.REQUIRE_MATCH)
					throw new IllegalArgumentException(MessageFormat
							.format(JGitText.get().invalidWildcards, spec));
			}
			srcName = checkValid(src);
			dstName = checkValid(dst);
		} else {
			if(isWildcard(s)) {
				if(mode == WildcardMode.REQUIRE_MATCH) {
					throw new IllegalArgumentException(MessageFormat
							.format(JGitText.get().invalidWildcards, spec));
				}
				wildcard = true;
			}
			srcName = checkValid(s);
		}

		if(isNegative()) {
			if(isNullOrEmpty(srcName) && isNullOrEmpty(dstName)) {
				throw new IllegalArgumentException(MessageFormat
						.format(JGitText.get().invalidRefSpec, spec));
			}
			if(!isNullOrEmpty(srcName) && !isNullOrEmpty(dstName)) {
				throw new IllegalArgumentException(MessageFormat
						.format(JGitText.get().invalidRefSpec, spec));
			}
			if(wildcard && mode == WildcardMode.REQUIRE_MATCH) {
				throw new IllegalArgumentException(MessageFormat
						.format(JGitText.get().invalidRefSpec, spec));
			}
		}
		matching = matchPushSpec;
	}

	public RefSpec(String spec) {
		this(spec, spec.startsWith("^") ? WildcardMode.ALLOW_MISMATCH
				: WildcardMode.REQUIRE_MATCH);
	}

	private RefSpec(RefSpec p) {
		matching = false;
		force = p.isForceUpdate();
		wildcard = p.isWildcard();
		negative = p.isNegative();
		srcName = p.getSource();
		dstName = p.getDestination();
		allowMismatchedWildcards = p.allowMismatchedWildcards;
	}

	public boolean isMatching() {
		return matching;
	}

	public boolean isForceUpdate() {
		return force;
	}

	public RefSpec setForceUpdate(boolean forceUpdate) {
		final RefSpec r = new RefSpec(this);
		if(forceUpdate && isNegative()) {
			throw new IllegalArgumentException(
					JGitText.get().invalidNegativeAndForce);
		}
		r.matching = matching;
		r.force = forceUpdate;
		return r;
	}

	public boolean isWildcard() {
		return wildcard;
	}

	public boolean isNegative() {
		return negative;
	}

	public String getSource() {
		return srcName;
	}

	public RefSpec setSource(String source) {
		final RefSpec r = new RefSpec(this);
		r.srcName = checkValid(source);
		if(isWildcard(r.srcName) && r.dstName == null)
			throw new IllegalStateException(JGitText.get().destinationIsNotAWildcard);
		if(isWildcard(r.srcName) != isWildcard(r.dstName))
			throw new IllegalStateException(JGitText.get().sourceDestinationMustMatch);
		return r;
	}

	public String getDestination() {
		return dstName;
	}

	public RefSpec setDestination(String destination) {
		final RefSpec r = new RefSpec(this);
		r.dstName = checkValid(destination);
		if(isWildcard(r.dstName) && r.srcName == null)
			throw new IllegalStateException(JGitText.get().sourceIsNotAWildcard);
		if(isWildcard(r.srcName) != isWildcard(r.dstName))
			throw new IllegalStateException(JGitText.get().sourceDestinationMustMatch);
		return r;
	}

	public RefSpec setSourceDestination(String source, String destination) {
		if(isWildcard(source) != isWildcard(destination))
			throw new IllegalStateException(JGitText.get().sourceDestinationMustMatch);
		final RefSpec r = new RefSpec(this);
		r.wildcard = isWildcard(source);
		r.srcName = source;
		r.dstName = destination;
		return r;
	}

	public boolean matchSource(String r) {
		return match(r, getSource());
	}

	public boolean matchSource(Ref r) {
		return match(r.getName(), getSource());
	}

	public boolean matchDestination(String r) {
		return match(r, getDestination());
	}

	public RefSpec expandFromSource(String r) {
		if(allowMismatchedWildcards != WildcardMode.REQUIRE_MATCH) {
			throw new IllegalStateException(
					JGitText.get().invalidExpandWildcard);
		}
		return isWildcard() ? new RefSpec(this).expandFromSourceImp(r) : this;
	}

	private RefSpec expandFromSourceImp(String name) {
		final String psrc = srcName, pdst = dstName;
		wildcard = false;
		srcName = name;
		dstName = expandWildcard(name, psrc, pdst);
		return this;
	}

	private static boolean isNullOrEmpty(String refName) {
		return refName == null || refName.isEmpty();
	}

	public RefSpec expandFromSource(Ref r) {
		return expandFromSource(r.getName());
	}

	public RefSpec expandFromDestination(String r) {
		if(allowMismatchedWildcards != WildcardMode.REQUIRE_MATCH) {
			throw new IllegalStateException(
					JGitText.get().invalidExpandWildcard);
		}
		return isWildcard() ? new RefSpec(this).expandFromDstImp(r) : this;
	}

	private RefSpec expandFromDstImp(String name) {
		final String psrc = srcName, pdst = dstName;
		wildcard = false;
		srcName = expandWildcard(name, pdst, psrc);
		dstName = name;
		return this;
	}

	private boolean match(String name, String s) {
		if(s == null)
			return false;
		if(isWildcard(s)) {
			int wildcardIndex = s.indexOf('*');
			String prefix = s.substring(0, wildcardIndex);
			String suffix = s.substring(wildcardIndex + 1);
			return name.length() > prefix.length() + suffix.length()
					&& name.startsWith(prefix) && name.endsWith(suffix);
		}
		return name.equals(s);
	}

	private static String expandWildcard(String name, String patternA,
										 String patternB) {
		int a = patternA.indexOf('*');
		int trailingA = patternA.length() - (a + 1);
		int b = patternB.indexOf('*');
		String match = name.substring(a, name.length() - trailingA);
		return patternB.substring(0, b) + match + patternB.substring(b + 1);
	}

	private static String checkValid(String spec) {
		if(spec != null && !isValid(spec))
			throw new IllegalArgumentException(MessageFormat.format(
					JGitText.get().invalidRefSpec, spec));
		return spec;
	}

	private static boolean isValid(String s) {
		if(s.startsWith("/"))
			return false;
		if(s.contains("//"))
			return false;
		if(s.endsWith("/"))
			return false;
		int i = s.indexOf('*');
		if(i != -1) {
			return s.indexOf('*', i + 1) <= i;
		}
		return true;
	}

	@Override
	public int hashCode() {
		int hc = 0;
		if(getSource() != null)
			hc = hc * 31 + getSource().hashCode();
		if(getDestination() != null)
			hc = hc * 31 + getDestination().hashCode();
		return hc;
	}

	@Override
	public boolean equals(Object obj) {
		if(!(obj instanceof RefSpec))
			return false;
		final RefSpec b = (RefSpec) obj;
		if(isForceUpdate() != b.isForceUpdate()) {
			return false;
		}
		if(isNegative() != b.isNegative()) {
			return false;
		}
		if(isMatching()) {
			return b.isMatching();
		} else if(b.isMatching()) {
			return false;
		}
		return isWildcard() == b.isWildcard()
				&& Objects.equals(getSource(), b.getSource())
				&& Objects.equals(getDestination(), b.getDestination());
	}

	@Override
	public String toString() {
		final StringBuilder r = new StringBuilder();
		if(isForceUpdate()) {
			r.append('+');
		}
		if(isNegative()) {
			r.append('^');
		}
		if(isMatching()) {
			r.append(':');
		} else {
			if(getSource() != null) {
				r.append(getSource());
			}
			if(getDestination() != null) {
				r.append(':');
				r.append(getDestination());
			}
		}
		return r.toString();
	}
}
