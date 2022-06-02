/*
 * Copyright (C) 2010, Stefan Lay <stefan.lay@sap.com>
 * Copyright (C) 2010-2012, Christian Halstrick <christian.halstrick@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.api;

import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.merge.MergeChunk;
import org.eclipse.jgit.merge.MergeChunk.ConflictState;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.ResolveMerger.MergeFailureReason;

public class MergeResult {

	public enum MergeStatus {
		FAST_FORWARD {
			@Override
			public String toString() {
				return "Fast-forward";
			}

			@Override
			public boolean isSuccessful() {
				return true;
			}
		},
		FAST_FORWARD_SQUASHED {
			@Override
			public String toString() {
				return "Fast-forward-squashed";
			}

			@Override
			public boolean isSuccessful() {
				return true;
			}
		},
		ALREADY_UP_TO_DATE {
			@Override
			public String toString() {
				return "Already-up-to-date";
			}

			@Override
			public boolean isSuccessful() {
				return true;
			}
		},
		FAILED {
			@Override
			public String toString() {
				return "Failed";
			}

			@Override
			public boolean isSuccessful() {
				return false;
			}
		},
		MERGED {
			@Override
			public String toString() {
				return "Merged";
			}

			@Override
			public boolean isSuccessful() {
				return true;
			}
		},
		MERGED_SQUASHED {
			@Override
			public String toString() {
				return "Merged-squashed";
			}

			@Override
			public boolean isSuccessful() {
				return true;
			}
		},
		MERGED_SQUASHED_NOT_COMMITTED {
			@Override
			public String toString() {
				return "Merged-squashed-not-committed";
			}

			@Override
			public boolean isSuccessful() {
				return true;
			}
		},
		CONFLICTING {
			@Override
			public String toString() {
				return "Conflicting";
			}

			@Override
			public boolean isSuccessful() {
				return false;
			}
		},
		ABORTED {
			@Override
			public String toString() {
				return "Aborted";
			}

			@Override
			public boolean isSuccessful() {
				return false;
			}
		},
		MERGED_NOT_COMMITTED {
			@Override
			public String toString() {
				return "Merged-not-committed";
			}

			@Override
			public boolean isSuccessful() {
				return true;
			}
		},
		NOT_SUPPORTED {
			@Override
			public String toString() {
				return "Not-yet-supported";
			}

			@Override
			public boolean isSuccessful() {
				return false;
			}
		},
		CHECKOUT_CONFLICT {
			@Override
			public String toString() {
				return "Checkout Conflict";
			}

			@Override
			public boolean isSuccessful() {
				return false;
			}
		};

		public abstract boolean isSuccessful();
	}

	private final ObjectId[] mergedCommits;
	private final ObjectId base;
	private Map<String, int[][]> conflicts;
	private final MergeStatus mergeStatus;
	private final String description;
	private final MergeStrategy mergeStrategy;

	public MergeResult(ObjectId base,
					   ObjectId[] mergedCommits, MergeStatus mergeStatus, MergeStrategy mergeStrategy,
					   Map<String, org.eclipse.jgit.merge.MergeResult<?>> lowLevelResults, String description) {
		this(base, mergedCommits, mergeStatus, mergeStrategy, lowLevelResults, null, description);
	}

	public MergeResult(ObjectId base,
					   ObjectId[] mergedCommits, MergeStatus mergeStatus, MergeStrategy mergeStrategy,
					   Map<String, org.eclipse.jgit.merge.MergeResult<?>> lowLevelResults,
					   Map<String, MergeFailureReason> failingPaths, String description) {
		this.mergedCommits = mergedCommits;
		this.base = base;
		this.mergeStatus = mergeStatus;
		this.mergeStrategy = mergeStrategy;
		this.description = description;
		if(lowLevelResults != null)
			for(Map.Entry<String, org.eclipse.jgit.merge.MergeResult<?>> result : lowLevelResults.entrySet())
				addConflict(result.getKey(), result.getValue());
	}

	public MergeStatus getMergeStatus() {
		return mergeStatus;
	}

	@Override
	public String toString() {
		boolean first = true;
		StringBuilder commits = new StringBuilder();
		for(ObjectId commit : mergedCommits) {
			if(!first) commits.append(", ");
			else first = false;
			commits.append(ObjectId.toString(commit));
		}
		return MessageFormat.format(JGitText.get().mergeUsingStrategyResultedInDescription,
				commits, ObjectId.toString(base), mergeStrategy.getName(),
				mergeStatus, (description == null ? "" : ", " + description));
	}

	public void addConflict(String path, org.eclipse.jgit.merge.MergeResult<?> lowLevelResult) {
		if(!lowLevelResult.containsConflicts()) return;
		if(conflicts == null) conflicts = new HashMap<>();
		int nrOfConflicts = 0;
		for(MergeChunk mergeChunk : lowLevelResult) {
			if(mergeChunk.getConflictState().equals(ConflictState.FIRST_CONFLICTING_RANGE)) {
				nrOfConflicts++;
			}
		}
		int currentConflict = -1;
		int[][] ret = new int[nrOfConflicts][mergedCommits.length + 1];
		for(MergeChunk mergeChunk : lowLevelResult) {
			int endOfChunk = 0;
			if(mergeChunk.getConflictState().equals(ConflictState.FIRST_CONFLICTING_RANGE)) {
				if(currentConflict > -1) {
					ret[currentConflict][mergedCommits.length] = endOfChunk;
				}
				currentConflict++;
				ret[currentConflict][mergeChunk.getSequenceIndex()] = mergeChunk.getBegin();
			}
			if(mergeChunk.getConflictState().equals(ConflictState.NEXT_CONFLICTING_RANGE)) {
				ret[currentConflict][mergeChunk.getSequenceIndex()] = mergeChunk.getBegin();
			}
		}
		conflicts.put(path, ret);
	}
}
