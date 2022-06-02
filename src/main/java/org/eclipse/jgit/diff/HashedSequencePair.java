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

public class HashedSequencePair<S extends Sequence> {

	private final SequenceComparator<? super S> cmp;
	private final S baseA;
	private final S baseB;
	private HashedSequence<S> cachedA;
	private HashedSequence<S> cachedB;

	public HashedSequencePair(SequenceComparator<? super S> cmp, S a, S b) {
		this.cmp = cmp;
		this.baseA = a;
		this.baseB = b;
	}

	public HashedSequenceComparator<S> getComparator() {
		return new HashedSequenceComparator<>(cmp);
	}

	public HashedSequence<S> getA() {
		if(cachedA == null)
			cachedA = wrap(baseA);
		return cachedA;
	}

	public HashedSequence<S> getB() {
		if(cachedB == null)
			cachedB = wrap(baseB);
		return cachedB;
	}

	private HashedSequence<S> wrap(S base) {
		final int end = base.size();
		final int[] hashes = new int[end];
		for(int ptr = 0; ptr < end; ptr++)
			hashes[ptr] = cmp.hash(base, ptr);
		return new HashedSequence<>(base, hashes);
	}
}
