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

import java.io.IOException;

class RewriteGenerator extends Generator {
	private static final int REWRITE = RevWalk.REWRITE;

	private static final int DUPLICATE = RevWalk.TEMP_MARK;

	private final Generator source;

	RewriteGenerator(Generator s) {
		super(s.firstParent);
		source = s;
	}

	@Override
	void shareFreeList(BlockRevQueue q) {
		source.shareFreeList(q);
	}

	@Override
	int outputType() {
		return source.outputType() & ~NEEDS_REWRITE;
	}

	@SuppressWarnings("ReferenceEquality")
	@Override
	RevCommit next() throws IOException {
		final RevCommit c = source.next();
		if(c == null) {
			return null;
		}
		boolean rewrote = false;
		final RevCommit[] pList = c.parents;
		final int nParents = pList.length;
		for(int i = 0; i < nParents; i++) {
			final RevCommit oldp = pList[i];
			final RevCommit newp = rewrite(oldp);
			if(firstParent) {
				if(newp == null) {
					c.parents = RevCommit.NO_PARENTS;
				} else {
					c.parents = new RevCommit[] {newp};
				}
				return c;
			}
			if(oldp != newp) {
				pList[i] = newp;
				rewrote = true;
			}
		}
		if(rewrote) {
			c.parents = cleanup(pList);
		}
		return c;
	}

	private RevCommit rewrite(RevCommit p) {
		for(; ; ) {
			final RevCommit[] pList = p.parents;
			if(pList.length > 1) {
				return p;
			}

			if((p.flags & RevWalk.UNINTERESTING) != 0) {
				return p;
			}

			if((p.flags & REWRITE) == 0) {
				return p;
			}

			if(pList.length == 0) {
				return null;
			}

			p = pList[0];
		}
	}

	private RevCommit[] cleanup(RevCommit[] oldList) {
		int newCnt = 0;
		for(int o = 0; o < oldList.length; o++) {
			final RevCommit p = oldList[o];
			if(p == null)
				continue;
			if((p.flags & DUPLICATE) != 0) {
				oldList[o] = null;
				continue;
			}
			p.flags |= DUPLICATE;
			newCnt++;
		}

		if(newCnt == oldList.length) {
			for(RevCommit p : oldList)
				p.flags &= ~DUPLICATE;
			return oldList;
		}

		final RevCommit[] newList = new RevCommit[newCnt];
		newCnt = 0;
		for(RevCommit p : oldList) {
			if(p != null) {
				newList[newCnt++] = p;
				p.flags &= ~DUPLICATE;
			}
		}

		return newList;
	}
}
