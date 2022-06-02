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

import org.eclipse.jgit.internal.JGitText;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.LinkedList;

class MergeBaseGenerator extends Generator {
	private static final int PARSED = RevWalk.PARSED;
	private static final int IN_PENDING = RevWalk.SEEN;
	private static final int POPPED = RevWalk.TEMP_MARK;
	private static final int MERGE_BASE = RevWalk.REWRITE;

	private final RevWalk walker;
	private final DateRevQueue pending;

	private int branchMask;
	private int recarryTest;
	private int recarryMask;
	private int mergeBaseAncestor = -1;
	private final LinkedList<RevCommit> ret = new LinkedList<>();

	private CarryStack stack;

	MergeBaseGenerator(RevWalk w) {
		super(w.isFirstParent());
		walker = w;
		pending = new DateRevQueue(firstParent);
	}

	void init(AbstractRevQueue p) throws IOException {
		try {
			for(; ; ) {
				final RevCommit c = p.next();
				if(c == null)
					break;
				add(c);
			}
			recarryTest = branchMask | POPPED;
			recarryMask = branchMask | POPPED | MERGE_BASE;
			mergeBaseAncestor = walker.allocFlag();

			for(; ; ) {
				RevCommit c = _next();
				if(c == null) {
					break;
				}
				ret.add(c);
			}
		} finally {
			walker.freeFlag(branchMask | mergeBaseAncestor);
		}
	}

	private void add(RevCommit c) {
		final int flag = walker.allocFlag();
		branchMask |= flag;
		if((c.flags & branchMask) != 0) {
			throw new IllegalStateException(MessageFormat.format(JGitText.get().staleRevFlagsOn, c.name()));
		}
		c.flags |= flag;
		pending.add(c);
	}

	@Override
	int outputType() {
		return 0;
	}

	private RevCommit _next() throws IOException {
		for(; ; ) {
			final RevCommit c = pending.next();
			if(c == null) {
				return null;
			}

			for(RevCommit p : c.parents) {
				if((p.flags & IN_PENDING) != 0)
					continue;
				if((p.flags & PARSED) == 0)
					p.parseHeaders(walker);
				p.flags |= IN_PENDING;
				pending.add(p);
			}

			int carry = c.flags & branchMask;
			boolean mb = carry == branchMask;
			if(mb) {
				carry |= MERGE_BASE | mergeBaseAncestor;
			}
			carryOntoHistory(c, carry);

			if((c.flags & MERGE_BASE) != 0) {
				if(pending.everbodyHasFlag(MERGE_BASE))
					return null;
				continue;
			}
			c.flags |= POPPED;

			if(mb) {
				c.flags |= MERGE_BASE;
				return c;
			}
		}
	}

	@Override
	RevCommit next() throws IOException {
		while(!ret.isEmpty()) {
			RevCommit commit = ret.remove();
			if((commit.flags & mergeBaseAncestor) == 0) {
				return commit;
			}
		}
		return null;
	}

	private void carryOntoHistory(RevCommit c, int carry) {
		stack = null;
		for(; ; ) {
			carryOntoHistoryInnerLoop(c, carry);
			if(stack == null) {
				break;
			}
			c = stack.c;
			carry = stack.carry;
			stack = stack.prev;
		}
	}

	private void carryOntoHistoryInnerLoop(RevCommit c, int carry) {
		for(; ; ) {
			RevCommit[] parents = c.parents;
			if(parents == null || parents.length == 0) {
				break;
			}

			int e = parents.length - 1;
			for(int i = 0; i < e; i++) {
				RevCommit p = parents[i];
				if(carryOntoOne(p, carry) == CONTINUE) {
					stack = new CarryStack(stack, p, carry);
				}
			}

			c = parents[e];
			if(carryOntoOne(c, carry) != CONTINUE) {
				break;
			}
		}
	}

	private static final int CONTINUE = 0;
	private static final int HAVE_ALL = 1;
	private static final int CONTINUE_ON_STACK = 2;

	private int carryOntoOne(RevCommit p, int carry) {
		int rc = (p.flags & carry) == carry ? HAVE_ALL : CONTINUE;
		p.flags |= carry;

		if((p.flags & recarryMask) == recarryTest) {
			p.flags &= ~POPPED;
			pending.add(p);
			stack = new CarryStack(stack, p, branchMask | MERGE_BASE);
			return CONTINUE_ON_STACK;
		}
		return rc;
	}

	private static class CarryStack {
		final CarryStack prev;
		final RevCommit c;
		final int carry;

		CarryStack(CarryStack prev, RevCommit c, int carry) {
			this.prev = prev;
			this.c = c;
			this.carry = carry;
		}
	}
}
