/*
 * Copyright (C) 2010, Google Inc.
 * Copyright (C) 2010, Marc Strapetz <marc.strapetz@syntevo.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.treewalk.filter;

import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.treewalk.TreeWalk;

public class SkipWorkTreeFilter extends TreeFilter {

	private final int treeIdx;

	public SkipWorkTreeFilter(int treeIdx) {
		this.treeIdx = treeIdx;
	}

	@Override
	public boolean include(TreeWalk walker) {
		DirCacheIterator i = walker.getTree(treeIdx);
		if (i == null)
			return true;

		DirCacheEntry e = i.getDirCacheEntry();
		return e == null || !e.isSkipWorkTree();
	}

	@Override
	public boolean shouldBeRecursive() {
		return false;
	}

	@Override
	public TreeFilter clone() {
		return this;
	}

	@SuppressWarnings("nls")
	@Override
	public String toString() {
		return "SkipWorkTree(" + treeIdx + ")";
	}
}
