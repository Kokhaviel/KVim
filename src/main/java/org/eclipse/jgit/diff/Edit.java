/*
 * Copyright (C) 2008-2009, Johannes E. Schindelin <johannes.schindelin@gmx.de> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.diff;

public class Edit {
	public enum Type {
		INSERT,
		DELETE,
		REPLACE,
		EMPTY
	}

	int beginA;
	int endA;
	int beginB;
	int endB;

	public Edit(int as, int bs) {
		this(as, as, bs, bs);
	}

	public Edit(int as, int ae, int bs, int be) {
		beginA = as;
		endA = ae;

		beginB = bs;
		endB = be;
	}

	public final Type getType() {
		if(beginA < endA) {
			if(beginB < endB) {
				return Type.REPLACE;
			}
			return Type.DELETE;

		}
		if(beginB < endB) {
			return Type.INSERT;
		}
		return Type.EMPTY;
	}

	public final boolean isEmpty() {
		return beginA == endA && beginB == endB;
	}

	public final int getBeginA() {
		return beginA;
	}

	public final int getEndA() {
		return endA;
	}

	public final int getBeginB() {
		return beginB;
	}

	public final int getEndB() {
		return endB;
	}

	public final int getLengthA() {
		return endA - beginA;
	}

	public final int getLengthB() {
		return endB - beginB;
	}

	public final void shift(int amount) {
		beginA += amount;
		endA += amount;
		beginB += amount;
		endB += amount;
	}

	public final Edit before(Edit cut) {
		return new Edit(beginA, cut.beginA, beginB, cut.beginB);
	}

	public final Edit after(Edit cut) {
		return new Edit(cut.endA, endA, cut.endB, endB);
	}

	public void extendA() {
		endA++;
	}

	public void extendB() {
		endB++;
	}

	@Override
	public int hashCode() {
		return beginA ^ endA;
	}

	@Override
	public boolean equals(Object o) {
		if(o instanceof Edit) {
			final Edit e = (Edit) o;
			return this.beginA == e.beginA && this.endA == e.endA
					&& this.beginB == e.beginB && this.endB == e.endB;
		}
		return false;
	}

	@Override
	public String toString() {
		final Type t = getType();
		return t + "(" + beginA + "-" + endA + "," + beginB + "-" + endB + ")";
	}
}
