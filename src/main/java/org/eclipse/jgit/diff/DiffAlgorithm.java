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

public abstract class DiffAlgorithm {

	public enum SupportedAlgorithm {
		MYERS,
		HISTOGRAM
	}

	public static DiffAlgorithm getAlgorithm(SupportedAlgorithm alg) {
		switch(alg) {
			case MYERS:
				return MyersDiff.INSTANCE;
			case HISTOGRAM:
				return new HistogramDiff();
			default:
				throw new IllegalArgumentException();
		}
	}

	public <S extends Sequence> EditList diff(
			SequenceComparator<? super S> cmp, S a, S b) {
		Edit region = cmp.reduceCommonStartEnd(a, b, coverEdit(a, b));

		switch(region.getType()) {
			case INSERT:
			case DELETE:
				return EditList.singleton(region);

			case REPLACE: {
				if(region.getLengthA() == 1 && region.getLengthB() == 1)
					return EditList.singleton(region);

				SubsequenceComparator<S> cs = new SubsequenceComparator<>(cmp);
				Subsequence<S> as = Subsequence.a(a, region);
				Subsequence<S> bs = Subsequence.b(b, region);
				EditList e = Subsequence.toBase(diffNonCommon(cs, as, bs), as, bs);
				return normalize(cmp, e, a, b);
			}

			case EMPTY:
				return new EditList(0);

			default:
				throw new IllegalStateException();
		}
	}

	private static <S extends Sequence> Edit coverEdit(S a, S b) {
		return new Edit(0, a.size(), 0, b.size());
	}

	private static <S extends Sequence> EditList normalize(
			SequenceComparator<? super S> cmp, EditList e, S a, S b) {
		Edit prev = null;
		for(int i = e.size() - 1; i >= 0; i--) {
			Edit cur = e.get(i);
			Edit.Type curType = cur.getType();

			int maxA = (prev == null) ? a.size() : prev.beginA;
			int maxB = (prev == null) ? b.size() : prev.beginB;

			if(curType == Edit.Type.INSERT) {
				while(cur.endA < maxA && cur.endB < maxB
						&& cmp.equals(b, cur.beginB, b, cur.endB)) {
					cur.shift(1);
				}
			} else if(curType == Edit.Type.DELETE) {
				while(cur.endA < maxA && cur.endB < maxB
						&& cmp.equals(a, cur.beginA, a, cur.endA)) {
					cur.shift(1);
				}
			}
			prev = cur;
		}
		return e;
	}

	public abstract <S extends Sequence> EditList diffNonCommon(
			SequenceComparator<? super S> cmp, S a, S b);
}
