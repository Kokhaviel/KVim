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

public final class SubsequenceComparator<S extends Sequence> extends SequenceComparator<Subsequence<S>> {
	private final SequenceComparator<? super S> cmp;

	public SubsequenceComparator(SequenceComparator<? super S> cmp) {
		this.cmp = cmp;
	}

	@Override
	public boolean equals(Subsequence<S> a, int ai, Subsequence<S> b, int bi) {
		return cmp.equals(a.base, ai + a.begin, b.base, bi + b.begin);
	}

	@Override
	public int hash(Subsequence<S> seq, int ptr) {
		return cmp.hash(seq.base, ptr + seq.begin);
	}
}
