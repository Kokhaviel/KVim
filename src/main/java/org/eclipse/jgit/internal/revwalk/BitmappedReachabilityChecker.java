/*
 * Copyright (C) 2019, Google LLC and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.internal.revwalk;

import org.eclipse.jgit.lib.BitmapIndex;
import org.eclipse.jgit.lib.BitmapIndex.Bitmap;
import org.eclipse.jgit.lib.BitmapIndex.BitmapBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.revwalk.*;
import org.eclipse.jgit.revwalk.filter.RevFilter;

import java.io.IOException;
import java.util.*;
import java.util.stream.Stream;

public class BitmappedReachabilityChecker implements ReachabilityChecker {

	private final RevWalk walk;

	public BitmappedReachabilityChecker(RevWalk walk)
			throws IOException {
		this.walk = walk;
		if(walk.getObjectReader().getBitmapIndex() == null) {
			throw new AssertionError("Trying to use bitmapped reachability check " + "on a repository without bitmaps");
		}
	}

	@Override
	public Optional<RevCommit> areAllReachable(Collection<RevCommit> targets, Stream<RevCommit> starters) throws IOException {

		List<RevCommit> remainingTargets = new ArrayList<>(targets);

		walk.reset();
		walk.sort(RevSort.TOPO);

		BitmapIndex repoBitmaps = walk.getObjectReader().getBitmapIndex();
		ReachedFilter reachedFilter = new ReachedFilter(repoBitmaps);
		walk.setRevFilter(reachedFilter);

		Iterator<RevCommit> startersIter = starters.iterator();
		while(startersIter.hasNext()) {
			walk.markStart(startersIter.next());
			while(walk.next() != null) {
				remainingTargets.removeIf(reachedFilter::isReachable);

				if(remainingTargets.isEmpty()) {
					return Optional.empty();
				}
			}
			walk.reset();
		}

		return Optional.of(remainingTargets.get(0));
	}

	private static class ReachedFilter extends RevFilter {

		private final BitmapIndex repoBitmaps;
		private final BitmapBuilder reached;

		public ReachedFilter(BitmapIndex repoBitmaps) {
			this.repoBitmaps = repoBitmaps;
			this.reached = repoBitmaps.newBitmapBuilder();
		}

		@Override
		public final boolean include(RevWalk walker, RevCommit cmit) {
			Bitmap commitBitmap;

			if(reached.contains(cmit)) {
				dontFollow(cmit);
				return false;
			}

			if((commitBitmap = repoBitmaps.getBitmap(cmit)) != null) {
				reached.or(commitBitmap);
				dontFollow(cmit);
				return true;
			}

			reached.addObject(cmit, Constants.OBJ_COMMIT);
			return true;
		}

		private static void dontFollow(RevCommit cmit) {
			for(RevCommit p : cmit.getParents()) {
				p.add(RevFlag.SEEN);
			}
		}

		@Override
		public final RevFilter clone() {
			throw new UnsupportedOperationException();
		}

		@Override
		public final boolean requiresCommitBody() {
			return false;
		}

		boolean isReachable(RevCommit commit) {
			return reached.contains(commit);
		}
	}
}
