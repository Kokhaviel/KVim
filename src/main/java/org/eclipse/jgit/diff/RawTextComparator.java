/*
 * Copyright (C) 2009-2010, Google Inc.
 * Copyright (C) 2008-2009, Johannes E. Schindelin <johannes.schindelin@gmx.de> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.diff;

import org.eclipse.jgit.util.IntList;

public abstract class RawTextComparator extends SequenceComparator<RawText> {

	public static final RawTextComparator DEFAULT = new RawTextComparator() {
		@Override
		public boolean equals(RawText a, int ai, RawText b, int bi) {
			ai++;
			bi++;

			int as = a.lines.get(ai);
			int bs = b.lines.get(bi);
			final int ae = a.lines.get(ai + 1);
			final int be = b.lines.get(bi + 1);

			if(ae - as != be - bs)
				return false;

			while(as < ae) {
				if(a.content[as++] != b.content[bs++])
					return false;
			}
			return true;
		}

		@Override
		protected int hashRegion(byte[] raw, int ptr, int end) {
			int hash = 5381;
			for(; ptr < end; ptr++)
				hash = ((hash << 5) + hash) + (raw[ptr] & 0xff);
			return hash;
		}
	};

	@Override
	public int hash(RawText seq, int lno) {
		final int begin = seq.lines.get(lno + 1);
		final int end = seq.lines.get(lno + 2);
		return hashRegion(seq.content, begin, end);
	}

	@Override
	public Edit reduceCommonStartEnd(RawText a, RawText b, Edit e) {

		if(e.beginA == e.endA || e.beginB == e.endB)
			return e;

		byte[] aRaw = a.content;
		byte[] bRaw = b.content;

		int aPtr = a.lines.get(e.beginA + 1);
		int bPtr = a.lines.get(e.beginB + 1);

		int aEnd = a.lines.get(e.endA + 1);
		int bEnd = b.lines.get(e.endB + 1);

		if(aPtr < 0 || bPtr < 0 || aEnd > aRaw.length || bEnd > bRaw.length)
			throw new ArrayIndexOutOfBoundsException();

		while(aPtr < aEnd && bPtr < bEnd && aRaw[aPtr] == bRaw[bPtr]) {
			aPtr++;
			bPtr++;
		}

		while(aPtr < aEnd && bPtr < bEnd && aRaw[aEnd - 1] == bRaw[bEnd - 1]) {
			aEnd--;
			bEnd--;
		}

		e.beginA = findForwardLine(a.lines, e.beginA, aPtr);
		e.beginB = findForwardLine(b.lines, e.beginB, bPtr);

		e.endA = findReverseLine(a.lines, e.endA, aEnd);

		final boolean partialA = aEnd < a.lines.get(e.endA + 1);
		if(partialA)
			bEnd += a.lines.get(e.endA + 1) - aEnd;

		e.endB = findReverseLine(b.lines, e.endB, bEnd);

		if(!partialA && bEnd < b.lines.get(e.endB + 1))
			e.endA++;

		return super.reduceCommonStartEnd(a, b, e);
	}

	private static int findForwardLine(IntList lines, int idx, int ptr) {
		final int end = lines.size() - 2;
		while(idx < end && lines.get(idx + 2) < ptr)
			idx++;
		return idx;
	}

	private static int findReverseLine(IntList lines, int idx, int ptr) {
		while(0 < idx && ptr <= lines.get(idx))
			idx--;
		return idx;
	}

	protected abstract int hashRegion(byte[] raw, int ptr, int end);
}
