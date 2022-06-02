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

public final class Subsequence<S extends Sequence> extends Sequence {

	public static <S extends Sequence> Subsequence<S> a(S a, Edit region) {
		return new Subsequence<>(a, region.beginA, region.endA);
	}

	public static <S extends Sequence> Subsequence<S> b(S b, Edit region) {
		return new Subsequence<>(b, region.beginB, region.endB);
	}

	public static <S extends Sequence> void toBase(Edit e, Subsequence<S> a,
												   Subsequence<S> b) {
		e.beginA += a.begin;
		e.endA += a.begin;

		e.beginB += b.begin;
		e.endB += b.begin;
	}

	public static <S extends Sequence> EditList toBase(EditList edits,
													   Subsequence<S> a, Subsequence<S> b) {
		for(Edit e : edits)
			toBase(e, a, b);
		return edits;
	}

	final S base;

	final int begin;

	private final int size;

	public Subsequence(S base, int begin, int end) {
		this.base = base;
		this.begin = begin;
		this.size = end - begin;
	}

	@Override
	public int size() {
		return size;
	}
}
