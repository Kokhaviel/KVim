/*
 * Copyright (C) 2009, Mark Struberg <struberg@yahoo.de>
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.revwalk.filter;

import java.io.IOException;
import java.util.Date;

import org.eclipse.jgit.errors.StopWalkException;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

public abstract class CommitTimeRevFilter extends RevFilter {

	public static RevFilter after(Date ts) {
		return after(ts.getTime());
	}

	public static RevFilter after(long ts) {
		return new After(ts);
	}

	final int when;

	CommitTimeRevFilter(long ts) {
		when = (int) (ts / 1000);
	}

	@Override
	public RevFilter clone() {
		return this;
	}

	@Override
	public boolean requiresCommitBody() {
		return false;
	}

	private static class After extends CommitTimeRevFilter {
		After(long ts) {
			super(ts);
		}

		@Override
		public boolean include(RevWalk walker, RevCommit cmit) throws StopWalkException, IOException {
			if (cmit.getCommitTime() < when)
				throw StopWalkException.INSTANCE;
			return true;
		}

		@Override
		public String toString() {
			return super.toString() + "(" + new Date(when * 1000L) + ")";
		}
	}

}
