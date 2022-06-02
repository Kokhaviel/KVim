/*
 * Copyright (C) 2010, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.pack;

class DeltaIndexScanner {
	final int[] table;
	final long[] entries;
	final int[] next;
	final int tableMask;
	private int entryCnt;

	DeltaIndexScanner(byte[] raw, int len) {
		len -= (len % DeltaIndex.BLKSZ);

		final int worstCaseBlockCnt = len / DeltaIndex.BLKSZ;
		if(worstCaseBlockCnt < 1) {
			table = new int[] {};
			tableMask = 0;

			entries = new long[] {};
			next = new int[] {};

		} else {
			table = new int[tableSize(worstCaseBlockCnt)];
			tableMask = table.length - 1;

			entries = new long[1 + worstCaseBlockCnt];
			next = new int[entries.length];

			scan(raw, len);
		}
	}

	private void scan(byte[] raw, int end) {
		int lastHash = 0;
		int ptr = end - DeltaIndex.BLKSZ;
		do {
			final int key = DeltaIndex.hashBlock(raw, ptr);
			final int tIdx = key & tableMask;

			final int head = table[tIdx];
			if(head != 0 && lastHash == key) {
				entries[head] = (((long) key) << 32) | ptr;
			} else {
				final int eIdx = ++entryCnt;
				entries[eIdx] = (((long) key) << 32) | ptr;
				next[eIdx] = head;
				table[tIdx] = eIdx;
			}

			lastHash = key;
			ptr -= DeltaIndex.BLKSZ;
		} while(0 <= ptr);
	}

	private static int tableSize(int worstCaseBlockCnt) {
		int shift = 32 - Integer.numberOfLeadingZeros(worstCaseBlockCnt);
		int sz = 1 << (shift - 1);
		if(sz < worstCaseBlockCnt)
			sz <<= 1;
		return sz;
	}
}
