/*
 * Copyright (C) 2010, Garmin International
 * Copyright (C) 2010, Matt Fischer <matt.fischer@garmin.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.revwalk;

import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.lib.ObjectId;

import java.io.IOException;

class DepthGenerator extends Generator {

	private final FIFORevQueue pending;
	private final int depth;
	private final int deepenSince;
	private final RevWalk walk;
	private final RevFlag UNSHALLOW;
	private final RevFlag REINTERESTING;
	private final RevFlag DEEPEN_NOT;

	DepthGenerator(DepthWalk w, Generator s) throws IOException {
		super(s.firstParent);
		pending = new FIFORevQueue(firstParent);
		walk = (RevWalk)w;

		this.depth = w.getDepth();
		this.deepenSince = w.getDeepenSince();
		this.UNSHALLOW = w.getUnshallowFlag();
		this.REINTERESTING = w.getReinterestingFlag();
		this.DEEPEN_NOT = w.getDeepenNotFlag();

		s.shareFreeList(pending);

		FIFORevQueue unshallowCommits = new FIFORevQueue();
		for (;;) {
			RevCommit c = s.next();
			if (c == null)
				break;
			if (c.has(UNSHALLOW)) {
				unshallowCommits.add(c);
			} else if (((DepthWalk.Commit) c).getDepth() == 0) {
				pending.add(c);
			}
		}

		for (;;) {
			RevCommit c = unshallowCommits.next();
			if (c == null) {
				break;
			}
			pending.unpop(c);
		}

		for (ObjectId oid : w.getDeepenNots()) {
			RevCommit c;
			try {
				c = walk.parseCommit(oid);
			} catch (IncorrectObjectTypeException notCommit) {
				continue;
			}

			FIFORevQueue queue = new FIFORevQueue();
			queue.add(c);
			while ((c = queue.next()) != null) {
				if (c.has(DEEPEN_NOT)) {
					continue;
				}

				walk.parseHeaders(c);
				c.add(DEEPEN_NOT);
				for (RevCommit p : c.getParents()) {
					queue.add(p);
				}
			}
		}
	}

	@Override
	int outputType() {
		return pending.outputType() | HAS_UNINTERESTING;
	}

	@Override
	void shareFreeList(BlockRevQueue q) {
		pending.shareFreeList(q);
	}

	@Override
	RevCommit next() throws IOException {
		for (;;) {
			final DepthWalk.Commit c = (DepthWalk.Commit) pending.next();
			if (c == null)
				return null;

			if ((c.flags & RevWalk.PARSED) == 0)
				c.parseHeaders(walk);

			if (c.getCommitTime() < deepenSince) {
				continue;
			}

			if (c.has(DEEPEN_NOT)) {
				continue;
			}

			int newDepth = c.depth + 1;

			for (int i = 0; i < c.parents.length; i++) {
				if (firstParent && i > 0) {
					break;
				}
				RevCommit p = c.parents[i];
				DepthWalk.Commit dp = (DepthWalk.Commit) p;

				if (dp.depth == -1) {
					boolean failsDeepenSince = false;
					if (deepenSince != 0) {
						if ((p.flags & RevWalk.PARSED) == 0) {
							p.parseHeaders(walk);
						}
						failsDeepenSince =
							p.getCommitTime() < deepenSince;
					}

					dp.depth = newDepth;

					if (newDepth <= depth && !failsDeepenSince &&
							!p.has(DEEPEN_NOT)) {
						pending.add(p);
					} else {
						dp.makesChildBoundary = true;
					}
				}

				if (dp.makesChildBoundary) {
					c.isBoundary = true;
				}

				if(c.has(UNSHALLOW) || c.has(REINTERESTING)) {
					p.add(REINTERESTING);
					p.flags &= ~RevWalk.UNINTERESTING;
				}
			}

			boolean produce = (c.flags & RevWalk.UNINTERESTING) == 0 || c.has(UNSHALLOW);

			if (c.getCommitTime() < deepenSince) {
				produce = false;
			}

			if (produce)
				return c;
		}
	}
}
