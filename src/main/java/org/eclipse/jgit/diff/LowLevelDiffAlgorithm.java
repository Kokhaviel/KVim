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

public abstract class LowLevelDiffAlgorithm extends DiffAlgorithm {

	@Override
	public <S extends Sequence> EditList diffNonCommon(
			SequenceComparator<? super S> cmp, S a, S b) {
		HashedSequencePair<S> p = new HashedSequencePair<>(cmp, a, b);
		HashedSequenceComparator<S> hc = p.getComparator();
		HashedSequence<S> ha = p.getA();
		HashedSequence<S> hb = p.getB();

		EditList res = new EditList();
		Edit region = new Edit(0, a.size(), 0, b.size());
		diffNonCommon(res, hc, ha, hb, region);
		return res;
	}

	public abstract <S extends Sequence>
	void diffNonCommon(EditList edits, HashedSequenceComparator<S> cmp, HashedSequence<S> a, HashedSequence<S> b, Edit region);
}
