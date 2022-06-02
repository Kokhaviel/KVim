/*
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.treewalk.filter;

import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;

public abstract class TreeFilter {
	public static final TreeFilter ALL = new AllFilter();

	private static final class AllFilter extends TreeFilter {
		@Override
		public boolean include(TreeWalk walker) {
			return true;
		}

		@Override
		public boolean shouldBeRecursive() {
			return false;
		}

		@Override
		public TreeFilter clone() {
			return this;
		}

		@Override
		public String toString() {
			return "ALL";
		}
	}

	public static final TreeFilter ANY_DIFF = new AnyDiffFilter();

	private static final class AnyDiffFilter extends TreeFilter {
		private static final int baseTree = 0;

		@Override
		public boolean include(TreeWalk walker) {
			final int n = walker.getTreeCount();
			if(n == 1)
				return true;

			final int m = walker.getRawMode(baseTree);
			for(int i = 1; i < n; i++)
				if(walker.getRawMode(i) != m || !walker.idEqual(i, baseTree))
					return true;
			return false;
		}

		@Override
		public boolean shouldBeRecursive() {
			return false;
		}

		@Override
		public TreeFilter clone() {
			return this;
		}

		@Override
		public String toString() {
			return "ANY_DIFF";
		}
	}

	public abstract boolean include(TreeWalk walker)
			throws IOException;

	public int matchFilter(TreeWalk walker)
			throws IOException {
		return include(walker) ? 0 : 1;
	}

	public abstract boolean shouldBeRecursive();

	@Override
	public abstract TreeFilter clone();

	@Override
	public String toString() {
		String n = getClass().getName();
		int lastDot = n.lastIndexOf('.');
		if(lastDot >= 0) {
			n = n.substring(lastDot + 1);
		}
		return n.replace('$', '.');
	}
}
