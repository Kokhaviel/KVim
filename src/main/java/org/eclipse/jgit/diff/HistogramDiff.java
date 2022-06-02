/*
 * Copyright (C) 2010, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.diff;

import java.util.ArrayList;
import java.util.List;

public class HistogramDiff extends LowLevelDiffAlgorithm {

	DiffAlgorithm fallback = MyersDiff.INSTANCE;
	int maxChainLength = 64;

	@Override
	public <S extends Sequence> void diffNonCommon(EditList edits,
												   HashedSequenceComparator<S> cmp, HashedSequence<S> a,
												   HashedSequence<S> b, Edit region) {
		new State<>(edits, cmp, a, b).diffRegion(region);
	}

	private class State<S extends Sequence> {
		private final HashedSequenceComparator<S> cmp;
		private final HashedSequence<S> a;
		private final HashedSequence<S> b;
		private final List<Edit> queue = new ArrayList<>();

		final EditList edits;

		State(EditList edits, HashedSequenceComparator<S> cmp,
			  HashedSequence<S> a, HashedSequence<S> b) {
			this.cmp = cmp;
			this.a = a;
			this.b = b;
			this.edits = edits;
		}

		void diffRegion(Edit r) {
			diffReplace(r);
			while(!queue.isEmpty())
				diff(queue.remove(queue.size() - 1));
		}

		private void diffReplace(Edit r) {
			Edit lcs = new HistogramDiffIndex<>(maxChainLength, cmp, a, b, r)
					.findLongestCommonSequence();
			if(lcs != null) {
				if(lcs.isEmpty()) {
					edits.add(r);
				} else {
					queue.add(r.after(lcs));
					queue.add(r.before(lcs));
				}

			} else if(fallback instanceof LowLevelDiffAlgorithm) {
				LowLevelDiffAlgorithm fb = (LowLevelDiffAlgorithm) fallback;
				fb.diffNonCommon(edits, cmp, a, b, r);

			} else if(fallback != null) {
				SubsequenceComparator<HashedSequence<S>> cs = subcmp();
				Subsequence<HashedSequence<S>> as = Subsequence.a(a, r);
				Subsequence<HashedSequence<S>> bs = Subsequence.b(b, r);

				EditList res = fallback.diffNonCommon(cs, as, bs);
				edits.addAll(Subsequence.toBase(res, as, bs));

			} else {
				edits.add(r);
			}
		}

		private void diff(Edit r) {
			switch(r.getType()) {
				case INSERT:
				case DELETE:
					edits.add(r);
					break;

				case REPLACE:
					if(r.getLengthA() == 1 && r.getLengthB() == 1)
						edits.add(r);
					else
						diffReplace(r);
					break;

				case EMPTY:
				default:
					throw new IllegalStateException();
			}
		}

		private SubsequenceComparator<HashedSequence<S>> subcmp() {
			return new SubsequenceComparator<>(cmp);
		}
	}
}
