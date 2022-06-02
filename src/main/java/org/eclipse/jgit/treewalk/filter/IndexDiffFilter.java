/*
 * Copyright (C) 2010, Christian Halstrick <christian.halstrick@sap.com> and others
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
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.WorkingTreeIterator;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;

public class IndexDiffFilter extends TreeFilter {

	private final int dirCache;
	private final int workingTree;
	private final boolean honorIgnores;
	private final Set<String> ignoredPaths = new HashSet<>();
	private final LinkedList<String> untrackedParentFolders = new LinkedList<>();
	private final LinkedList<String> untrackedFolders = new LinkedList<>();

	public IndexDiffFilter(int dirCacheIndex, int workingTreeIndex) {
		this(dirCacheIndex, workingTreeIndex, true);
	}

	public IndexDiffFilter(int dirCacheIndex, int workingTreeIndex,
						   boolean honorIgnores) {
		this.dirCache = dirCacheIndex;
		this.workingTree = workingTreeIndex;
		this.honorIgnores = honorIgnores;
	}

	@Override
	public boolean include(TreeWalk tw) throws
			IOException {
		final int cnt = tw.getTreeCount();
		final int wm = tw.getRawMode(workingTree);
		WorkingTreeIterator wi = workingTree(tw);
		String path = tw.getPathString();

		DirCacheIterator di = tw.getTree(dirCache);
		if(di != null) {
			DirCacheEntry dce = di.getDirCacheEntry();
			if(dce != null) {
				if(dce.isAssumeValid())
					return false;
				if(dce.getStage() != 0)
					return true;
			}
		}

		if(!tw.isPostOrderTraversal()) {
			if(FileMode.TREE.equals(wm)
					&& !(honorIgnores && wi.isEntryIgnored())) {
				copyUntrackedFolders(path);
				untrackedParentFolders.addFirst(path);
			}

			for(int i = 0; i < cnt; i++) {
				int rmode = tw.getRawMode(i);
				if(i != workingTree && rmode != FileMode.TYPE_MISSING
						&& FileMode.TREE.equals(rmode)) {
					untrackedParentFolders.clear();
					break;
				}
			}
		}

		if(wm == 0)
			return true;

		final int dm = tw.getRawMode(dirCache);
		if(dm == FileMode.TYPE_MISSING) {
			if(honorIgnores && wi.isEntryIgnored()) {
				ignoredPaths.add(wi.getEntryPathString());
				int i = 0;
				for(; i < cnt; i++) {
					if(i == dirCache || i == workingTree)
						continue;
					if(tw.getRawMode(i) != FileMode.TYPE_MISSING)
						break;
				}

				return i != cnt;
			}
			return true;
		}

		if(tw.isSubtree())
			return true;

		for(int i = 0; i < cnt; i++) {
			if(i == dirCache || i == workingTree)
				continue;
			if(tw.getRawMode(i) != dm || !tw.idEqual(i, dirCache))
				return true;
		}

		return wi.isModified(di == null ? null : di.getDirCacheEntry(), true,
				tw.getObjectReader());
	}

	private void copyUntrackedFolders(String currentPath) {
		String pathToBeSaved = null;
		while(!untrackedParentFolders.isEmpty() && !currentPath
				.startsWith(untrackedParentFolders.getFirst() + '/')) {
			pathToBeSaved = untrackedParentFolders.removeFirst();
		}
		if(pathToBeSaved != null) {
			while(!untrackedFolders.isEmpty() && untrackedFolders.getLast()
					.startsWith(pathToBeSaved + '/')) {
				untrackedFolders.removeLast();
			}
			untrackedFolders.addLast(pathToBeSaved);
		}
	}

	private WorkingTreeIterator workingTree(TreeWalk tw) {
		return tw.getTree(workingTree);
	}

	@Override
	public boolean shouldBeRecursive() {
		return true;
	}

	@Override
	public TreeFilter clone() {
		return this;
	}

	@Override
	public String toString() {
		return "INDEX_DIFF_FILTER";
	}

	public Set<String> getIgnoredPaths() {
		return ignoredPaths;
	}

}
