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

public final class HashedSequenceComparator<S extends Sequence> extends SequenceComparator<HashedSequence<S>> {
	private final SequenceComparator<? super S> cmp;

	HashedSequenceComparator(SequenceComparator<? super S> cmp) {
		this.cmp = cmp;
	}

	@Override
	public boolean equals(HashedSequence<S> a, int ai,
						  HashedSequence<S> b, int bi) {
		return a.hashes[ai] == b.hashes[bi]
				&& cmp.equals(a.base, ai, b.base, bi);
	}

	@Override
	public int hash(HashedSequence<S> seq, int ptr) {
		return seq.hashes[ptr];
	}
}
