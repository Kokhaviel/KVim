/*
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.revwalk;

import java.text.MessageFormat;

import org.eclipse.jgit.internal.JGitText;

public class RevFlag {

	public static final RevFlag UNINTERESTING = new StaticRevFlag("UNINTERESTING", RevWalk.UNINTERESTING);

	public static final RevFlag SEEN = new StaticRevFlag("SEEN", RevWalk.SEEN);

	final RevWalk walker;
	final String name;
	final int mask;

	RevFlag(RevWalk w, String n, int m) {
		walker = w;
		name = n;
		mask = m;
	}

	public RevWalk getRevWalk() {
		return walker;
	}

	@Override
	public String toString() {
		return name;
	}

	static class StaticRevFlag extends RevFlag {
		StaticRevFlag(String n, int m) {
			super(null, n, m);
		}

		@Override
		public RevWalk getRevWalk() {
			throw new UnsupportedOperationException(MessageFormat.format(
					JGitText.get().isAStaticFlagAndHasNorevWalkInstance, toString()));
		}
	}
}
