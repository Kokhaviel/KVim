/*
 * Copyright (C) 2007, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.treewalk.filter;

import java.util.Collection;

import org.eclipse.jgit.errors.StopWalkException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.ByteArraySet.Hasher;
import org.eclipse.jgit.util.RawParseUtils;

public class PathFilterGroup {

	public static TreeFilter createFromStrings(Collection<String> paths) {
		if(paths.isEmpty())
			throw new IllegalArgumentException(JGitText.get().atLeastOnePathIsRequired);
		final PathFilter[] p = new PathFilter[paths.size()];
		int i = 0;
		for(String s : paths)
			p[i++] = PathFilter.create(s);
		return create(p);
	}

	public static TreeFilter create(Collection<PathFilter> paths) {
		if(paths.isEmpty())
			throw new IllegalArgumentException(
					JGitText.get().atLeastOnePathIsRequired);
		final PathFilter[] p = new PathFilter[paths.size()];
		paths.toArray(p);
		return create(p);
	}

	private static TreeFilter create(PathFilter[] p) {
		if(p.length == 1)
			return new Single(p[0]);
		return new Group(p);
	}

	static class Single extends TreeFilter {
		private final PathFilter path;

		private final byte[] raw;

		private Single(PathFilter p) {
			path = p;
			raw = path.pathRaw;
		}

		@Override
		public boolean include(TreeWalk walker) {
			final int cmp = walker.isPathPrefix(raw, raw.length);
			if(cmp > 0)
				throw StopWalkException.INSTANCE;
			return cmp == 0;
		}

		@Override
		public boolean shouldBeRecursive() {
			return path.shouldBeRecursive();
		}

		@Override
		public TreeFilter clone() {
			return this;
		}

		@Override
		public String toString() {
			return "FAST_" + path;
		}
	}

	static class Group extends TreeFilter {

		private final ByteArraySet fullpaths;

		private final ByteArraySet prefixes;

		private byte[] max;

		private Group(PathFilter[] pathFilters) {
			fullpaths = new ByteArraySet(pathFilters.length);
			prefixes = new ByteArraySet(pathFilters.length / 5);
			max = pathFilters[0].pathRaw;
			Hasher hasher = new Hasher(null, 0);
			for(PathFilter pf : pathFilters) {
				hasher.init(pf.pathRaw, pf.pathRaw.length);
				while(hasher.hasNext()) {
					int hash = hasher.nextHash();
					if(hasher.hasNext())
						prefixes.addIfAbsent(pf.pathRaw, hasher.length(), hash);
				}
				fullpaths.addIfAbsent(pf.pathRaw, pf.pathRaw.length,
						hasher.getHash());
				if(compare(max, pf.pathRaw) < 0)
					max = pf.pathRaw;
			}
			byte[] newMax = new byte[max.length + 1];
			for(int i = 0; i < max.length; ++i)
				if((max[i] & 0xFF) < '/')
					newMax[i] = '/';
				else
					newMax[i] = max[i];
			newMax[newMax.length - 1] = '/';
			max = newMax;
		}

		private static int compare(byte[] a, byte[] b) {
			int i = 0;
			while(i < a.length && i < b.length) {
				int ba = a[i] & 0xFF;
				int bb = b[i] & 0xFF;
				int cmp = ba - bb;
				if(cmp != 0)
					return cmp;
				++i;
			}
			return a.length - b.length;
		}

		@Override
		public boolean include(TreeWalk walker) {

			byte[] rp = walker.getRawPath();
			Hasher hasher = new Hasher(rp, walker.getPathLength());
			while(hasher.hasNext()) {
				int hash = hasher.nextHash();
				if(fullpaths.contains(rp, hasher.length(), hash))
					return true;
				if(!hasher.hasNext() && walker.isSubtree()
						&& prefixes.contains(rp, hasher.length(), hash))
					return true;
			}

			final int cmp = walker.isPathPrefix(max, max.length);
			if(cmp > 0)
				throw StopWalkException.INSTANCE;

			return false;
		}

		@Override
		public boolean shouldBeRecursive() {
			return !prefixes.isEmpty();
		}

		@Override
		public TreeFilter clone() {
			return this;
		}

		@Override
		public String toString() {
			final StringBuilder r = new StringBuilder();
			r.append("FAST(");
			boolean first = true;
			for(byte[] p : fullpaths.toArray()) {
				if(!first) {
					r.append(" OR ");
				}
				r.append(RawParseUtils.decode(p));
				first = false;
			}
			r.append(")");
			return r.toString();
		}
	}
}
