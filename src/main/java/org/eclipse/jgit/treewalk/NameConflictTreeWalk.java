/*
 * Copyright (C) 2008, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.treewalk;

import java.io.IOException;

import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;

public class NameConflictTreeWalk extends TreeWalk {
	private static final int TREE_MODE = FileMode.TREE.getBits();

	private boolean fastMinHasMatch;

	private AbstractTreeIterator dfConflict;

	public NameConflictTreeWalk(Repository repo) {
		super(repo);
	}

	public NameConflictTreeWalk(@Nullable Repository repo, ObjectReader or) {
		super(repo, or);
	}

	@Override
	AbstractTreeIterator min() throws CorruptObjectException {
		for(; ; ) {
			final AbstractTreeIterator minRef = fastMin();
			if(fastMinHasMatch)
				return minRef;

			if(isTree(minRef)) {
				if(skipEntry(minRef)) {
					for(AbstractTreeIterator t : trees) {
						if(t.matches == minRef) {
							t.next(1);
							t.matches = null;
						}
					}
					continue;
				}
				return minRef;
			}

			return combineDF(minRef);
		}
	}

	private AbstractTreeIterator fastMin() {
		fastMinHasMatch = true;

		int i = 0;
		AbstractTreeIterator minRef = trees[i];
		while(minRef.eof() && ++i < trees.length)
			minRef = trees[i];
		if(minRef.eof())
			return minRef;

		boolean hasConflict = false;
		minRef.matches = minRef;
		while(++i < trees.length) {
			final AbstractTreeIterator t = trees[i];
			if(t.eof())
				continue;

			final int cmp = t.pathCompare(minRef);
			if(cmp < 0) {
				if(fastMinHasMatch && isTree(minRef) && !isTree(t)
						&& nameEqual(minRef, t)) {
					t.matches = minRef;
					hasConflict = true;
				} else {
					fastMinHasMatch = false;
					t.matches = t;
					minRef = t;
				}
			} else if(cmp == 0) {
				t.matches = minRef;
			} else if(fastMinHasMatch && isTree(t) && !isTree(minRef)
					&& !isGitlink(minRef) && nameEqual(t, minRef)) {

				for(int k = 0; k < i; k++) {
					final AbstractTreeIterator p = trees[k];
					if(p.matches == minRef)
						p.matches = t;
				}
				t.matches = t;
				minRef = t;
				hasConflict = true;
			} else
				fastMinHasMatch = false;
		}

		if(hasConflict && fastMinHasMatch && dfConflict == null)
			dfConflict = minRef;
		return minRef;
	}

	private static boolean nameEqual(final AbstractTreeIterator a,
									 final AbstractTreeIterator b) {
		return a.pathCompare(b, TREE_MODE) == 0;
	}

	private boolean isGitlink(AbstractTreeIterator p) {
		return FileMode.GITLINK.equals(p.mode);
	}

	private static boolean isTree(AbstractTreeIterator p) {
		return FileMode.TREE.equals(p.mode);
	}

	private boolean skipEntry(AbstractTreeIterator minRef)
			throws CorruptObjectException {
		for(AbstractTreeIterator t : trees) {
			if(t.matches == minRef || t.first())
				continue;

			int stepsBack = 0;
			for(; ; ) {
				stepsBack++;
				t.back(1);

				final int cmp = t.pathCompare(minRef, 0);
				if(cmp == 0) {
					t.next(stepsBack);
					return true;
				} else if(cmp < 0 || t.first()) {
					t.next(stepsBack);
					break;
				}
			}
		}

		return false;
	}

	private AbstractTreeIterator combineDF(AbstractTreeIterator minRef)
			throws CorruptObjectException {
		AbstractTreeIterator treeMatch = null;
		for(AbstractTreeIterator t : trees) {
			if(t.matches == minRef || t.eof())
				continue;

			for(; ; ) {
				final int cmp = t.pathCompare(minRef, TREE_MODE);
				if(cmp < 0) {
					t.matchShift++;
					t.next(1);
					if(t.eof()) {
						t.back(t.matchShift);
						t.matchShift = 0;
						break;
					}
				} else if(cmp == 0) {
					t.matches = minRef;
					treeMatch = t;
					break;
				} else {
					if(t.matchShift != 0) {
						t.back(t.matchShift);
						t.matchShift = 0;
					}
					break;
				}
			}
		}

		if(treeMatch != null) {
			for(AbstractTreeIterator t : trees)
				if(t.matches == minRef)
					t.matches = treeMatch;

			if(dfConflict == null && !isGitlink(minRef)) {
				dfConflict = treeMatch;
			}

			return treeMatch;
		}

		return minRef;
	}

	@Override
	void popEntriesEqual() throws CorruptObjectException {
		final AbstractTreeIterator ch = currentHead;
		for(AbstractTreeIterator t : trees) {
			if(t.matches == ch) {
				if(t.matchShift == 0)
					t.next(1);
				else {
					t.back(t.matchShift);
					t.matchShift = 0;
				}
				t.matches = null;
			}
		}

		if(ch == dfConflict)
			dfConflict = null;
	}

	@Override
	void skipEntriesEqual() throws CorruptObjectException {
		final AbstractTreeIterator ch = currentHead;
		for(AbstractTreeIterator t : trees) {
			if(t.matches == ch) {
				if(t.matchShift == 0)
					t.skip();
				else {
					t.back(t.matchShift);
					t.matchShift = 0;
				}
				t.matches = null;
			}
		}

		if(ch == dfConflict)
			dfConflict = null;
	}

	@Override
	void stopWalk() throws IOException {
		if(!needsStopWalk()) {
			return;
		}

		for(; ; ) {
			AbstractTreeIterator t = min();
			if(t.eof()) {
				if(depth > 0) {
					exitSubtree();
					popEntriesEqual();
					continue;
				}
				return;
			}
			currentHead = t;
			skipEntriesEqual();
		}
	}

	private boolean needsStopWalk() {
		for(AbstractTreeIterator t : trees) {
			if(t.needsStopWalk()) {
				return true;
			}
		}
		return false;
	}

	public boolean isDirectoryFileConflict() {
		return dfConflict != null;
	}
}
