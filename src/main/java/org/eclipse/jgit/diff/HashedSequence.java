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

public final class HashedSequence<S extends Sequence> extends Sequence {
	final S base;

	final int[] hashes;

	HashedSequence(S base, int[] hashes) {
		this.base = base;
		this.hashes = hashes;
	}

	@Override
	public int size() {
		return base.size();
	}
}
