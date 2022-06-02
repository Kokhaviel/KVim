/*
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.revwalk.filter;

import org.eclipse.jgit.errors.StopWalkException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

import java.io.IOException;

public abstract class RevFilter {
	public static final RevFilter ALL = new AllFilter();

	private static final class AllFilter extends RevFilter {
		@Override
		public boolean include(RevWalk walker, RevCommit c) {
			return true;
		}

		@Override
		public RevFilter clone() {
			return this;
		}

		@Override
		public boolean requiresCommitBody() {
			return false;
		}

		@Override
		public String toString() {
			return "ALL";
		}
	}

	public static final RevFilter NONE = new NoneFilter();

	private static final class NoneFilter extends RevFilter {
		@Override
		public boolean include(RevWalk walker, RevCommit c) {
			return false;
		}

		@Override
		public RevFilter clone() {
			return this;
		}

		@Override
		public boolean requiresCommitBody() {
			return false;
		}

		@Override
		public String toString() {
			return "NONE";
		}
	}

	public static final RevFilter MERGE_BASE = new MergeBaseFilter();

	private static final class MergeBaseFilter extends RevFilter {
		@Override
		public boolean include(RevWalk walker, RevCommit c) {
			throw new UnsupportedOperationException(JGitText.get().cannotBeCombined);
		}

		@Override
		public RevFilter clone() {
			return this;
		}

		@Override
		public boolean requiresCommitBody() {
			return false;
		}

		@Override
		public String toString() {
			return "MERGE_BASE";
		}
	}

	public boolean requiresCommitBody() {
		return true;
	}

	public abstract boolean include(RevWalk walker, RevCommit cmit)
			throws StopWalkException, IOException;

	@Override
	public abstract RevFilter clone();

	@Override
	public String toString() {
		String n = getClass().getName();
		int lastDot = n.lastIndexOf('.');
		if(lastDot >= 0) {
			n = n.substring(lastDot + 1);
		}
		return n.replace('$', '.');
	}
}
