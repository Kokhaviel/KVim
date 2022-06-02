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

import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectStream;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

public class SimilarityIndex {
	public static final TableFullException TABLE_FULL_OUT_OF_MEMORY = new TableFullException();

	private static final int KEY_SHIFT = 32;
	private static final long MAX_COUNT = (1L << KEY_SHIFT) - 1;

	private long hashedCnt;
	private int idSize;
	private int idGrowAt;
	private long[] idHash;
	private int idHashBits;

	SimilarityIndex() {
		idHashBits = 8;
		idHash = new long[1 << idHashBits];
		idGrowAt = growAt(idHashBits);
	}

	static boolean isBinary(ObjectLoader obj) throws IOException {
		if(obj.isLarge()) {
			try(ObjectStream in1 = obj.openStream()) {
				return RawText.isBinary(in1);
			}
		}
		byte[] raw = obj.getCachedBytes();
		return RawText.isBinary(raw, raw.length, true);
	}

	void hash(ObjectLoader obj) throws IOException,
			TableFullException {
		if(obj.isLarge()) {
			hashLargeObject(obj);
		} else {
			byte[] raw = obj.getCachedBytes();
			hash(raw, 0, raw.length);
		}
	}

	private void hashLargeObject(ObjectLoader obj) throws IOException,
			TableFullException {
		boolean text;
		text = !isBinary(obj);

		try(ObjectStream in2 = obj.openStream()) {
			hash(in2, in2.getSize(), text);
		}
	}

	void hash(byte[] raw, int ptr, int end) throws TableFullException {
		final boolean text = !RawText.isBinary(raw, raw.length, true);
		hashedCnt = 0;
		while(ptr < end) {
			int hash = 5381;
			int blockHashedCnt = 0;
			int start = ptr;

			do {
				int c = raw[ptr++] & 0xff;
				if(text && c == '\r' && ptr < end && raw[ptr] == '\n')
					continue;
				blockHashedCnt++;
				if(c == '\n')
					break;
				hash = (hash << 5) + hash + c;
			} while(ptr < end && ptr - start < 64);
			hashedCnt += blockHashedCnt;
			add(hash, blockHashedCnt);
		}
	}

	void hash(InputStream in, long remaining, boolean text) throws IOException, TableFullException {
		byte[] buf = new byte[4096];
		int ptr = 0;
		int cnt = 0;

		while(0 < remaining) {
			int hash = 5381;
			int blockHashedCnt = 0;

			int n = 0;
			do {
				if(ptr == cnt) {
					ptr = 0;
					cnt = in.read(buf, 0, buf.length);
					if(cnt <= 0)
						throw new EOFException();
				}

				n++;
				int c = buf[ptr++] & 0xff;
				if(text && c == '\r' && ptr < cnt && buf[ptr] == '\n')
					continue;
				blockHashedCnt++;
				if(c == '\n')
					break;
				hash = (hash << 5) + hash + c;
			} while(n < 64 && n < remaining);
			hashedCnt += blockHashedCnt;
			add(hash, blockHashedCnt);
			remaining -= n;
		}
	}

	void sort() {
		Arrays.sort(idHash);
	}

	public int score(SimilarityIndex dst, int maxScore) {
		long max = Math.max(hashedCnt, dst.hashedCnt);
		if(max == 0)
			return maxScore;
		return (int) ((common(dst) * maxScore) / max);
	}

	long common(SimilarityIndex dst) {
		return common(this, dst);
	}

	private static long common(SimilarityIndex src, SimilarityIndex dst) {
		int srcIdx = src.packedIndex();
		int dstIdx = dst.packedIndex();
		long[] srcHash = src.idHash;
		long[] dstHash = dst.idHash;
		return common(srcHash, srcIdx, dstHash, dstIdx);
	}

	private static long common(long[] srcHash, int srcIdx, //
							   long[] dstHash, int dstIdx) {
		if(srcIdx == srcHash.length || dstIdx == dstHash.length)
			return 0;

		long common = 0;
		int srcKey = keyOf(srcHash[srcIdx]);
		int dstKey = keyOf(dstHash[dstIdx]);

		for(; ; ) {
			if(srcKey == dstKey) {
				common += Math.min(countOf(srcHash[srcIdx]),
						countOf(dstHash[dstIdx]));

				if(++srcIdx == srcHash.length)
					break;
				srcKey = keyOf(srcHash[srcIdx]);

				if(++dstIdx == dstHash.length)
					break;
				dstKey = keyOf(dstHash[dstIdx]);

			} else if(srcKey < dstKey) {
				if(++srcIdx == srcHash.length)
					break;
				srcKey = keyOf(srcHash[srcIdx]);

			} else {
				if(++dstIdx == dstHash.length)
					break;
				dstKey = keyOf(dstHash[dstIdx]);
			}
		}

		return common;
	}

	private int packedIndex() {
		return (idHash.length - idSize);
	}

	void add(int key, int cnt) throws TableFullException {
		key = (key * 0x9e370001) >>> 1;

		int j = slot(key);
		for(; ; ) {
			long v = idHash[j];
			if(v == 0) {
				if(idGrowAt <= idSize) {
					grow();
					j = slot(key);
					continue;
				}
				idHash[j] = pair(key, cnt);
				idSize++;
				return;

			} else if(keyOf(v) == key) {
				idHash[j] = pair(key, countOf(v) + cnt);
				return;

			} else if(++j >= idHash.length) {
				j = 0;
			}
		}
	}

	private static long pair(int key, long cnt) throws TableFullException {
		if(MAX_COUNT < cnt)
			throw new TableFullException();
		return (((long) key) << KEY_SHIFT) | cnt;
	}

	private int slot(int key) {
		return key >>> (31 - idHashBits);
	}

	private static int growAt(int idHashBits) {
		return (1 << idHashBits) * (idHashBits - 3) / idHashBits;
	}

	private void grow() throws TableFullException {
		if(idHashBits == 30)
			throw new TableFullException();

		long[] oldHash = idHash;
		int oldSize = idHash.length;

		idHashBits++;
		idGrowAt = growAt(idHashBits);

		try {
			idHash = new long[1 << idHashBits];
		} catch(OutOfMemoryError noMemory) {
			throw TABLE_FULL_OUT_OF_MEMORY;
		}

		for(int i = 0; i < oldSize; i++) {
			long v = oldHash[i];
			if(v != 0) {
				int j = slot(keyOf(v));
				while(idHash[j] != 0)
					if(++j >= idHash.length)
						j = 0;
				idHash[j] = v;
			}
		}
	}

	private static int keyOf(long v) {
		return (int) (v >>> KEY_SHIFT);
	}

	private static long countOf(long v) {
		return v & MAX_COUNT;
	}

	public static class TableFullException extends Exception {
		private static final long serialVersionUID = 1L;
	}
}
