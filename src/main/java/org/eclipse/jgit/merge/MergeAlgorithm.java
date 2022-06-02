/*
 * Copyright (C) 2009, Christian Halstrick <christian.halstrick@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.merge;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.Sequence;
import org.eclipse.jgit.diff.SequenceComparator;
import org.eclipse.jgit.merge.MergeChunk.ConflictState;

public final class MergeAlgorithm {

	private final DiffAlgorithm diffAlg;

	@NonNull
	private ContentMergeStrategy strategy = ContentMergeStrategy.CONFLICT;

	public MergeAlgorithm(DiffAlgorithm diff) {
		this.diffAlg = diff;
	}

	public void setContentMergeStrategy(ContentMergeStrategy strategy) {
		this.strategy = strategy == null ? ContentMergeStrategy.CONFLICT : strategy;
	}

	private static final Edit END_EDIT = new Edit(Integer.MAX_VALUE,
			Integer.MAX_VALUE);

	private static boolean isEndEdit(Edit edit) {
		return edit == END_EDIT;
	}

	public <S extends Sequence> MergeResult<S> merge(
			SequenceComparator<S> cmp, S base, S ours, S theirs) {
		List<S> sequences = new ArrayList<>(3);
		sequences.add(base);
		sequences.add(ours);
		sequences.add(theirs);
		MergeResult<S> result = new MergeResult<>(sequences);

		if(ours.size() == 0) {
			if(theirs.size() != 0) {
				EditList theirsEdits = diffAlg.diff(cmp, base, theirs);
				if(!theirsEdits.isEmpty()) {
					switch(strategy) {
						case OURS:
							result.add(1, 0, 0, ConflictState.NO_CONFLICT);
							break;
						case THEIRS:
							result.add(2, 0, theirs.size(),
									ConflictState.NO_CONFLICT);
							break;
						default:
							result.add(1, 0, 0,
									ConflictState.FIRST_CONFLICTING_RANGE);
							result.add(2, 0, theirs.size(),
									ConflictState.NEXT_CONFLICTING_RANGE);
							break;
					}
				} else {
					result.add(1, 0, 0, ConflictState.NO_CONFLICT);
				}
			} else {
				result.add(1, 0, 0, ConflictState.NO_CONFLICT);
			}
			return result;
		} else if(theirs.size() == 0) {
			EditList oursEdits = diffAlg.diff(cmp, base, ours);
			if(!oursEdits.isEmpty()) {
				switch(strategy) {
					case OURS:
						result.add(1, 0, ours.size(), ConflictState.NO_CONFLICT);
						break;
					case THEIRS:
						result.add(2, 0, 0, ConflictState.NO_CONFLICT);
						break;
					default:
						result.add(1, 0, ours.size(),
								ConflictState.FIRST_CONFLICTING_RANGE);
						result.add(2, 0, 0, ConflictState.NEXT_CONFLICTING_RANGE);
						break;
				}
			} else {
				result.add(2, 0, 0, ConflictState.NO_CONFLICT);
			}
			return result;
		}

		EditList oursEdits = diffAlg.diff(cmp, base, ours);
		Iterator<Edit> baseToOurs = oursEdits.iterator();
		EditList theirsEdits = diffAlg.diff(cmp, base, theirs);
		Iterator<Edit> baseToTheirs = theirsEdits.iterator();
		int current = 0;
		Edit oursEdit = nextEdit(baseToOurs);
		Edit theirsEdit = nextEdit(baseToTheirs);

		while(!isEndEdit(theirsEdit) || !isEndEdit(oursEdit)) {
			if(oursEdit.getEndA() < theirsEdit.getBeginA()) {
				if(current != oursEdit.getBeginA()) {
					result.add(0, current, oursEdit.getBeginA(),
							ConflictState.NO_CONFLICT);
				}
				result.add(1, oursEdit.getBeginB(), oursEdit.getEndB(),
						ConflictState.NO_CONFLICT);
				current = oursEdit.getEndA();
				oursEdit = nextEdit(baseToOurs);
			} else if(theirsEdit.getEndA() < oursEdit.getBeginA()) {
				if(current != theirsEdit.getBeginA()) {
					result.add(0, current, theirsEdit.getBeginA(),
							ConflictState.NO_CONFLICT);
				}
				result.add(2, theirsEdit.getBeginB(), theirsEdit.getEndB(),
						ConflictState.NO_CONFLICT);
				current = theirsEdit.getEndA();
				theirsEdit = nextEdit(baseToTheirs);
			} else {
				if(oursEdit.getBeginA() != current
						&& theirsEdit.getBeginA() != current) {
					result.add(0, current, Math.min(oursEdit.getBeginA(),
							theirsEdit.getBeginA()), ConflictState.NO_CONFLICT);
				}

				int oursBeginB = oursEdit.getBeginB();
				int theirsBeginB = theirsEdit.getBeginB();
				if(oursEdit.getBeginA() < theirsEdit.getBeginA()) {
					theirsBeginB -= theirsEdit.getBeginA()
							- oursEdit.getBeginA();
				} else {
					oursBeginB -= oursEdit.getBeginA() - theirsEdit.getBeginA();
				}

				Edit nextOursEdit = nextEdit(baseToOurs);
				Edit nextTheirsEdit = nextEdit(baseToTheirs);
				for(; ; ) {
					if(oursEdit.getEndA() >= nextTheirsEdit.getBeginA()) {
						theirsEdit = nextTheirsEdit;
						nextTheirsEdit = nextEdit(baseToTheirs);
					} else if(theirsEdit.getEndA() >= nextOursEdit.getBeginA()) {
						oursEdit = nextOursEdit;
						nextOursEdit = nextEdit(baseToOurs);
					} else {
						break;
					}
				}

				int oursEndB = oursEdit.getEndB();
				int theirsEndB = theirsEdit.getEndB();
				if(oursEdit.getEndA() < theirsEdit.getEndA()) {
					oursEndB += theirsEdit.getEndA() - oursEdit.getEndA();
				} else {
					theirsEndB += oursEdit.getEndA() - theirsEdit.getEndA();
				}

				int minBSize = oursEndB - oursBeginB;
				int BSizeDelta = minBSize - (theirsEndB - theirsBeginB);
				if(BSizeDelta > 0)
					minBSize -= BSizeDelta;

				int commonPrefix = 0;
				while(commonPrefix < minBSize
						&& cmp.equals(ours, oursBeginB + commonPrefix, theirs,
						theirsBeginB + commonPrefix))
					commonPrefix++;
				minBSize -= commonPrefix;
				int commonSuffix = 0;
				while(commonSuffix < minBSize
						&& cmp.equals(ours, oursEndB - commonSuffix - 1, theirs,
						theirsEndB - commonSuffix - 1))
					commonSuffix++;
				minBSize -= commonSuffix;

				if(commonPrefix > 0)
					result.add(1, oursBeginB, oursBeginB + commonPrefix,
							ConflictState.NO_CONFLICT);

				if(minBSize > 0 || BSizeDelta != 0) {
					switch(strategy) {
						case OURS:
							result.add(1, oursBeginB + commonPrefix,
									oursEndB - commonSuffix,
									ConflictState.NO_CONFLICT);
							break;
						case THEIRS:
							result.add(2, theirsBeginB + commonPrefix,
									theirsEndB - commonSuffix,
									ConflictState.NO_CONFLICT);
							break;
						default:
							result.add(1, oursBeginB + commonPrefix,
									oursEndB - commonSuffix,
									ConflictState.FIRST_CONFLICTING_RANGE);
							result.add(2, theirsBeginB + commonPrefix,
									theirsEndB - commonSuffix,
									ConflictState.NEXT_CONFLICTING_RANGE);
							break;
					}
				}

				if(commonSuffix > 0)
					result.add(1, oursEndB - commonSuffix, oursEndB,
							ConflictState.NO_CONFLICT);

				current = Math.max(oursEdit.getEndA(), theirsEdit.getEndA());
				oursEdit = nextOursEdit;
				theirsEdit = nextTheirsEdit;
			}
		}

		if(current < base.size()) {
			result.add(0, current, base.size(), ConflictState.NO_CONFLICT);
		}
		return result;
	}

	private static Edit nextEdit(Iterator<Edit> it) {
		return (it.hasNext() ? it.next() : END_EDIT);
	}
}
