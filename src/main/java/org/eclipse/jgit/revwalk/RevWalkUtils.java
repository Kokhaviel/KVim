/*
 * Copyright (C) 2011-2012, Robin Stocker <robin@nibor.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.revwalk;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Ref;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class RevWalkUtils {

	private RevWalkUtils() {
	}

	public static int count(final RevWalk walk, final RevCommit start,
							final RevCommit end) throws
			IOException {
		return find(walk, start, end).size();
	}

	public static List<RevCommit> find(final RevWalk walk,
									   final RevCommit start, final RevCommit end)
			throws
			IOException {
		walk.reset();
		walk.markStart(start);
		if(end != null)
			walk.markUninteresting(end);

		List<RevCommit> commits = new ArrayList<>();
		for(RevCommit c : walk)
			commits.add(c);
		return commits;
	}

	public static List<Ref> findBranchesReachableFrom(RevCommit commit,
													  RevWalk revWalk, Collection<Ref> refs)
			throws
			IOException {
		return findBranchesReachableFrom(commit, revWalk, refs,
				NullProgressMonitor.INSTANCE);
	}

	public static List<Ref> findBranchesReachableFrom(RevCommit commit,
													  RevWalk revWalk, Collection<Ref> refs, ProgressMonitor monitor)
			throws
			IOException {

		commit = revWalk.parseCommit(commit.getId());
		revWalk.reset();
		List<Ref> filteredRefs = new ArrayList<>();
		monitor.beginTask(JGitText.get().searchForReachableBranches,
				refs.size());
		final int SKEW = 24 * 3600;

		for(Ref ref : refs) {
			RevObject maybehead = revWalk.parseAny(ref.getObjectId());
			if(!(maybehead instanceof RevCommit))
				continue;
			RevCommit headCommit = (RevCommit) maybehead;

			if(headCommit.getCommitTime() + SKEW < commit.getCommitTime())
				continue;

			filteredRefs.add(ref);
		}
		List<Ref> result = revWalk.getMergedInto(commit, filteredRefs, monitor);
		monitor.endTask();
		return result;
	}

}
