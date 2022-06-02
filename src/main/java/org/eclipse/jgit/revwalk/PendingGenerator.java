/*
 * Copyright (C) 2009, Google Inc.
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.revwalk;

import org.eclipse.jgit.errors.StopWalkException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.filter.RevFilter;

import java.io.IOException;

class PendingGenerator extends Generator {
	private static final int PARSED = RevWalk.PARSED;
	private static final int SEEN = RevWalk.SEEN;
	private static final int UNINTERESTING = RevWalk.UNINTERESTING;
	static final int OVER_SCAN = 5 + 1;
	private static final RevCommit INIT_LAST;

	static {
		INIT_LAST = new RevCommit(ObjectId.zeroId());
		INIT_LAST.commitTime = Integer.MAX_VALUE;
	}

	private final RevWalk walker;
	private final DateRevQueue pending;
	private final RevFilter filter;
	private final int output;
	private RevCommit last = INIT_LAST;
	private int overScan = OVER_SCAN;
	boolean canDispose;

	PendingGenerator(final RevWalk w, final DateRevQueue p,
					 final RevFilter f, final int out) {
		super(w.isFirstParent());
		walker = w;
		pending = p;
		filter = f;
		output = out;
		canDispose = true;
	}

	@Override
	int outputType() {
		return output | SORT_COMMIT_TIME_DESC;
	}

	@Override
	RevCommit next() throws IOException {
		try {
			for(; ; ) {
				final RevCommit c = pending.next();
				if(c == null) {
					return null;
				}

				final boolean produce;
				if((c.flags & UNINTERESTING) != 0)
					produce = false;
				else {
					if(filter.requiresCommitBody())
						c.parseBody(walker);
					produce = filter.include(walker, c);
				}

				for(int i = 0; i < c.parents.length; i++) {
					RevCommit p = c.parents[i];
					if(firstParent && i > 0 && (c.flags & UNINTERESTING) == 0) {
						continue;
					}
					if((p.flags & SEEN) != 0)
						continue;
					if((p.flags & PARSED) == 0)
						p.parseHeaders(walker);
					p.flags |= SEEN;
					pending.add(p);
				}
				walker.carryFlagsImpl(c);

				if((c.flags & UNINTERESTING) != 0) {
					if(pending.everbodyHasFlag(UNINTERESTING)) {
						final RevCommit n = pending.peek();
						if(n != null && n.commitTime >= last.commitTime) {
							overScan = OVER_SCAN;
						} else if(--overScan == 0)
							throw StopWalkException.INSTANCE;
					} else {
						overScan = OVER_SCAN;
					}
					if(canDispose)
						c.disposeBody();
					continue;
				}

				if(produce)
					return last = c;
				else if(canDispose)
					c.disposeBody();
			}
		} catch(StopWalkException swe) {
			pending.clear();
			return null;
		}
	}
}
