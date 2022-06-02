/*
 * Copyright (C) 2020, Google LLC. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.revwalk;

import java.io.IOException;

class TopoNonIntermixSortGenerator extends Generator {

	private static final int TOPO_QUEUED = RevWalk.TOPO_QUEUED;
	private final FIFORevQueue pending;
	private final int outputType;

	TopoNonIntermixSortGenerator(Generator s) throws IOException {
		super(s.firstParent);
		pending = new FIFORevQueue(firstParent);
		outputType = s.outputType() | SORT_TOPO;
		s.shareFreeList(pending);
		for(; ; ) {
			final RevCommit c = s.next();
			if(c == null) {
				break;
			}
			if((c.flags & TOPO_QUEUED) == 0) {
				for(RevCommit p : c.parents) {
					p.inDegree++;

					if(firstParent) {
						break;
					}
				}
			}
			c.flags |= TOPO_QUEUED;
			pending.add(c);
		}
	}

	@Override
	int outputType() {
		return outputType;
	}

	@Override
	void shareFreeList(BlockRevQueue q) {
		q.shareFreeList(pending);
	}

	@Override
	RevCommit next() throws IOException {
		for(; ; ) {
			final RevCommit c = pending.next();
			if(c == null) {
				return null;
			}

			if(c.inDegree > 0) {
				continue;
			}

			if((c.flags & TOPO_QUEUED) == 0) {
				continue;
			}

			for(RevCommit p : c.parents) {
				if(--p.inDegree == 0 && (p.flags & TOPO_QUEUED) != 0) {
					pending.unpop(p);
				}
				if(firstParent) {
					break;
				}
			}

			c.flags &= ~TOPO_QUEUED;
			return c;
		}
	}
}
