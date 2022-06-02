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

public abstract class SequenceComparator<S extends Sequence> {

	public abstract boolean equals(S a, int ai, S b, int bi);

	public abstract int hash(S seq, int ptr);

	public Edit reduceCommonStartEnd(S a, S b, Edit e) {
		while (e.beginA < e.endA && e.beginB < e.endB
				&& equals(a, e.beginA, b, e.beginB)) {
			e.beginA++;
			e.beginB++;
		}

		while (e.beginA < e.endA && e.beginB < e.endB
				&& equals(a, e.endA - 1, b, e.endB - 1)) {
			e.endA--;
			e.endB--;
		}

		return e;
	}
}
