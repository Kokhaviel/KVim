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

import org.eclipse.jgit.diff.DiffConfig;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffEntry.ChangeType;
import org.eclipse.jgit.diff.RenameDetector;
import org.eclipse.jgit.errors.StopWalkException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

import java.io.IOException;
import java.util.List;

public class TreeRevFilter extends RevFilter {
	private static final int PARSED = RevWalk.PARSED;

	private static final int UNINTERESTING = RevWalk.UNINTERESTING;

	private final int rewriteFlag;
	private final TreeWalk pathFilter;

	TreeRevFilter(RevWalk walker, TreeFilter t, int rewriteFlag) {
		pathFilter = new TreeWalk(walker.reader);
		pathFilter.setFilter(t);
		pathFilter.setRecursive(t.shouldBeRecursive());
		this.rewriteFlag = rewriteFlag;
	}

	@Override
	public RevFilter clone() {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean include(RevWalk walker, RevCommit c)
			throws StopWalkException, IOException {
		RevCommit[] pList = c.parents;
		int nParents = pList.length;
		TreeWalk tw = pathFilter;
		ObjectId[] trees = new ObjectId[nParents + 1];
		for(int i = 0; i < nParents; i++) {
			RevCommit p = c.parents[i];
			if((p.flags & PARSED) == 0) {
				p.parseHeaders(walker);
			}
			trees[i] = p.getTree();
		}
		trees[nParents] = c.getTree();
		tw.reset(trees);

		if(nParents == 1) {
			int chgs = 0, adds = 0;
			while(tw.next()) {
				chgs++;
				if(tw.getRawMode(0) == 0 && tw.getRawMode(1) != 0) {
					adds++;
				} else {
					break;
				}
			}

			if(chgs == 0) {
				c.flags |= rewriteFlag;
				return false;
			}

			if(adds > 0 && tw.getFilter() instanceof FollowFilter) {
				updateFollowFilter(trees, ((FollowFilter) tw.getFilter()).cfg);
			}
			return true;
		} else if(nParents == 0) {
			if(tw.next()) {
				return true;
			}
			c.flags |= rewriteFlag;
			return false;
		}

		int[] chgs = new int[nParents];
		int[] adds = new int[nParents];
		while(tw.next()) {
			int myMode = tw.getRawMode(nParents);
			for(int i = 0; i < nParents; i++) {
				int pMode = tw.getRawMode(i);
				if(myMode == pMode && tw.idEqual(i, nParents)) {
					continue;
				}
				chgs[i]++;
				if(pMode == 0 && myMode != 0) {
					adds[i]++;
				}
			}
		}

		boolean same = false;
		boolean diff = false;
		for(int i = 0; i < nParents; i++) {
			if(chgs[i] == 0) {

				RevCommit p = pList[i];
				if((p.flags & UNINTERESTING) != 0) {
					same = true;
					continue;
				}

				c.flags |= rewriteFlag;
				c.parents = new RevCommit[] {p};
				return false;
			}

			if(chgs[i] == adds[i]) {
				tw.reset(pList[i].getTree());
				if(!tw.next()) {
					pList[i].parents = RevCommit.NO_PARENTS;
				}
			}

			diff = true;
		}

		if(diff && !same) {
			return true;
		}

		c.flags |= rewriteFlag;
		return false;
	}

	@Override
	public boolean requiresCommitBody() {
		return false;
	}

	private void updateFollowFilter(ObjectId[] trees, DiffConfig cfg)
			throws IOException {
		TreeWalk tw = pathFilter;
		FollowFilter oldFilter = (FollowFilter) tw.getFilter();
		tw.setFilter(TreeFilter.ANY_DIFF);
		tw.reset(trees);

		List<DiffEntry> files = DiffEntry.scan(tw);
		RenameDetector rd = new RenameDetector(tw.getObjectReader(), cfg);
		rd.addAll(files);
		files = rd.compute();

		FollowFilter newFilter = oldFilter;
		for(DiffEntry ent : files) {
			if(isRename(ent) && ent.getNewPath().equals(oldFilter.getPath())) {
				newFilter = FollowFilter.create(ent.getOldPath(), cfg);
				RenameCallback callback = oldFilter.getRenameCallback();
				if(callback != null) {
					callback.renamed(ent);
					newFilter.setRenameCallback(callback);
				}
				break;
			}
		}
		tw.setFilter(newFilter);
	}

	private static boolean isRename(DiffEntry ent) {
		return ent.getChangeType() == ChangeType.RENAME
				|| ent.getChangeType() == ChangeType.COPY;
	}
}
