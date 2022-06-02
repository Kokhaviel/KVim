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

import org.eclipse.jgit.internal.JGitText;

final class HistogramDiffIndex<S extends Sequence> {

	private static final int REC_NEXT_SHIFT = 28 + 8;
	private static final int REC_PTR_SHIFT = 8;
	private static final int REC_PTR_MASK = (1 << 28) - 1;
	private static final int REC_CNT_MASK = (1 << 8) - 1;
	private static final int MAX_PTR = REC_PTR_MASK;
	private static final int MAX_CNT = (1 << 8) - 1;

	private final int maxChainLength;
	private final HashedSequenceComparator<S> cmp;
	private final HashedSequence<S> a;
	private final HashedSequence<S> b;
	private final Edit region;
	private final int[] table;
	private final int keyShift;
	private long[] recs;
	private int recCnt;
	private final int[] next;
	private final int[] recIdx;
	private final int ptrShift;
	private Edit lcs;
	private int cnt;
	private boolean hasCommon;

	HistogramDiffIndex(int maxChainLength, HashedSequenceComparator<S> cmp,
					   HashedSequence<S> a, HashedSequence<S> b, Edit r) {
		this.maxChainLength = maxChainLength;
		this.cmp = cmp;
		this.a = a;
		this.b = b;
		this.region = r;

		if(region.endA >= MAX_PTR)
			throw new IllegalArgumentException(
					JGitText.get().sequenceTooLargeForDiffAlgorithm);

		final int sz = r.getLengthA();
		final int tableBits = tableBits(sz);
		table = new int[1 << tableBits];
		keyShift = 32 - tableBits;
		ptrShift = r.beginA;

		recs = new long[Math.max(4, sz >>> 3)];
		next = new int[sz];
		recIdx = new int[sz];
	}

	Edit findLongestCommonSequence() {
		if(!scanA())
			return null;

		lcs = new Edit(0, 0);
		cnt = maxChainLength + 1;

		for(int bPtr = region.beginB; bPtr < region.endB; )
			bPtr = tryLongestCommonSequence(bPtr);

		return hasCommon && maxChainLength < cnt ? null : lcs;
	}

	private boolean scanA() {
		SCAN:
		for(int ptr = region.endA - 1; region.beginA <= ptr; ptr--) {
			final int tIdx = hash(a, ptr);

			int chainLen = 0;
			for(int rIdx = table[tIdx]; rIdx != 0; ) {
				final long rec = recs[rIdx];
				if(cmp.equals(a, recPtr(rec), a, ptr)) {
					int newCnt = recCnt(rec) + 1;
					if(MAX_CNT < newCnt)
						newCnt = MAX_CNT;
					recs[rIdx] = recCreate(recNext(rec), ptr, newCnt);
					next[ptr - ptrShift] = recPtr(rec);
					recIdx[ptr - ptrShift] = rIdx;
					continue SCAN;
				}

				rIdx = recNext(rec);
				chainLen++;
			}

			if(chainLen == maxChainLength)
				return false;

			final int rIdx = ++recCnt;
			if(rIdx == recs.length) {
				int sz = Math.min(recs.length << 1, 1 + region.getLengthA());
				long[] n = new long[sz];
				System.arraycopy(recs, 0, n, 0, recs.length);
				recs = n;
			}

			recs[rIdx] = recCreate(table[tIdx], ptr, 1);
			recIdx[ptr - ptrShift] = rIdx;
			table[tIdx] = rIdx;
		}
		return true;
	}

	private int tryLongestCommonSequence(int bPtr) {
		int bNext = bPtr + 1;
		int rIdx = table[hash(b, bPtr)];
		for(long rec; rIdx != 0; rIdx = recNext(rec)) {
			rec = recs[rIdx];

			if(recCnt(rec) > cnt) {
				if(!hasCommon)
					hasCommon = cmp.equals(a, recPtr(rec), b, bPtr);
				continue;
			}

			int as = recPtr(rec);
			if(!cmp.equals(a, as, b, bPtr))
				continue;

			hasCommon = true;
			TRY_LOCATIONS:
			for(; ; ) {
				int np = next[as - ptrShift];
				int bs = bPtr;
				int ae = as + 1;
				int be = bs + 1;
				int rc = recCnt(rec);

				while(region.beginA < as && region.beginB < bs
						&& cmp.equals(a, as - 1, b, bs - 1)) {
					as--;
					bs--;
					if(1 < rc)
						rc = Math.min(rc, recCnt(recs[recIdx[as - ptrShift]]));
				}
				while(ae < region.endA && be < region.endB
						&& cmp.equals(a, ae, b, be)) {
					if(1 < rc)
						rc = Math.min(rc, recCnt(recs[recIdx[ae - ptrShift]]));
					ae++;
					be++;
				}

				if(bNext < be)
					bNext = be;
				if(lcs.getLengthA() < ae - as || rc < cnt) {
					lcs.beginA = as;
					lcs.beginB = bs;
					lcs.endA = ae;
					lcs.endB = be;
					cnt = rc;
				}

				if(np == 0)
					break;

				while(np < ae) {
					np = next[np - ptrShift];
					if(np == 0)
						break TRY_LOCATIONS;
				}

				as = np;
			}
		}
		return bNext;
	}

	private int hash(HashedSequence<S> s, int idx) {
		return (cmp.hash(s, idx) * 0x9e370001) >>> keyShift;
	}

	private static long recCreate(int next, int ptr, int cnt) {
		return ((long) next << REC_NEXT_SHIFT) | ((long) ptr << REC_PTR_SHIFT) | cnt;
	}

	private static int recNext(long rec) {
		return (int) (rec >>> REC_NEXT_SHIFT);
	}

	private static int recPtr(long rec) {
		return ((int) (rec >>> REC_PTR_SHIFT)) & REC_PTR_MASK;
	}

	private static int recCnt(long rec) {
		return ((int) rec) & REC_CNT_MASK;
	}

	private static int tableBits(int sz) {
		int bits = 31 - Integer.numberOfLeadingZeros(sz);
		if(bits == 0)
			bits = 1;
		if(1 << bits < sz)
			bits++;
		return bits;
	}
}
