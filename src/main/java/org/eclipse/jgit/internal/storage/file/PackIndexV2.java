/*
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.file;

import java.io.IOException;
import java.io.InputStream;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;

import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.util.IO;
import org.eclipse.jgit.util.NB;

class PackIndexV2 extends PackIndex {
	private static final long IS_O64 = 1L << 31;
	private static final int FANOUT = 256;
	private static final int[] NO_INTS = {};
	private static final byte[] NO_BYTES = {};

	private final long objectCnt;
	private final long[] fanoutTable;
	int[][] names;
	byte[][] offset32;
	private final byte[][] crc32;
	byte[] offset64;

	PackIndexV2(final InputStream fd) throws IOException {
		final byte[] fanoutRaw = new byte[4 * FANOUT];
		IO.readFully(fd, fanoutRaw, 0, fanoutRaw.length);
		fanoutTable = new long[FANOUT];
		for(int k = 0; k < FANOUT; k++)
			fanoutTable[k] = NB.decodeUInt32(fanoutRaw, k * 4);
		objectCnt = fanoutTable[FANOUT - 1];

		names = new int[FANOUT][];
		offset32 = new byte[FANOUT][];
		crc32 = new byte[FANOUT][];

		for(int k = 0; k < FANOUT; k++) {
			final long bucketCnt;
			if(k == 0)
				bucketCnt = fanoutTable[k];
			else
				bucketCnt = fanoutTable[k] - fanoutTable[k - 1];

			if(bucketCnt == 0) {
				names[k] = NO_INTS;
				offset32[k] = NO_BYTES;
				crc32[k] = NO_BYTES;
				continue;
			} else if(bucketCnt < 0)
				throw new IOException(MessageFormat.format(JGitText.get().indexFileCorruptedNegativeBucketCount, bucketCnt));

			final long nameLen = bucketCnt * Constants.OBJECT_ID_LENGTH;
			if(nameLen > Integer.MAX_VALUE - 8) throw new IOException(JGitText.get().indexFileIsTooLargeForJgit);

			final int intNameLen = (int) nameLen;
			final byte[] raw = new byte[intNameLen];
			final int[] bin = new int[intNameLen >>> 2];
			IO.readFully(fd, raw, 0, raw.length);
			for(int i = 0; i < bin.length; i++)
				bin[i] = NB.decodeInt32(raw, i << 2);

			names[k] = bin;
			offset32[k] = new byte[(int) (bucketCnt * 4)];
			crc32[k] = new byte[(int) (bucketCnt * 4)];
		}

		for(int k = 0; k < FANOUT; k++) IO.readFully(fd, crc32[k], 0, crc32[k].length);

		int o64cnt = 0;
		for(int k = 0; k < FANOUT; k++) {
			final byte[] ofs = offset32[k];
			IO.readFully(fd, ofs, 0, ofs.length);
			for(int p = 0; p < ofs.length; p += 4) if(ofs[p] < 0) o64cnt++;
		}

		if(o64cnt > 0) {
			offset64 = new byte[o64cnt * 8];
			IO.readFully(fd, offset64, 0, offset64.length);
		} else {
			offset64 = NO_BYTES;
		}

		packChecksum = new byte[20];
		IO.readFully(fd, packChecksum, 0, packChecksum.length);
	}

	@Override
	public long getObjectCount() {
		return objectCnt;
	}

	private int findLevelOne(long nthPosition) {
		int levelOne = Arrays.binarySearch(fanoutTable, nthPosition + 1);
		if(levelOne >= 0) {
			long base = fanoutTable[levelOne];
			while(levelOne > 0 && base == fanoutTable[levelOne - 1])
				levelOne--;
		} else {
			levelOne = -(levelOne + 1);
		}
		return levelOne;
	}

	private int getLevelTwo(long nthPosition, int levelOne) {
		final long base = levelOne > 0 ? fanoutTable[levelOne - 1] : 0;
		return (int) (nthPosition - base);
	}

	@Override
	public ObjectId getObjectId(long nthPosition) {
		final int levelOne = findLevelOne(nthPosition);
		final int p = getLevelTwo(nthPosition, levelOne);
		final int p4 = p << 2;
		return ObjectId.fromRaw(names[levelOne], p4 + p);
	}

	@Override
	public long getOffset(long nthPosition) {
		final int levelOne = findLevelOne(nthPosition);
		final int levelTwo = getLevelTwo(nthPosition, levelOne);
		return getOffset(levelOne, levelTwo);
	}

	@Override
	public long findOffset(AnyObjectId objId) {
		final int levelOne = objId.getFirstByte();
		final int levelTwo = binarySearchLevelTwo(objId, levelOne);
		if(levelTwo == -1)
			return -1;
		return getOffset(levelOne, levelTwo);
	}

	private long getOffset(int levelOne, int levelTwo) {
		final long p = NB.decodeUInt32(offset32[levelOne], levelTwo << 2);
		if((p & IS_O64) != 0)
			return NB.decodeUInt64(offset64, (8 * (int) (p & ~IS_O64)));
		return p;
	}

	@Override
	public long findCRC32(AnyObjectId objId) throws MissingObjectException {
		final int levelOne = objId.getFirstByte();
		final int levelTwo = binarySearchLevelTwo(objId, levelOne);
		if(levelTwo == -1)
			throw new MissingObjectException(objId.copy(), "unknown");
		return NB.decodeUInt32(crc32[levelOne], levelTwo << 2);
	}

	@Override
	public boolean hasCRC32Support() {
		return true;
	}

	@Override
	public Iterator<MutableEntry> iterator() {
		return new EntriesIteratorV2();
	}

	@Override
	public void resolve(Set<ObjectId> matches, AbbreviatedObjectId id,
						int matchLimit) throws IOException {
		int[] data = names[id.getFirstByte()];
		int max = offset32[id.getFirstByte()].length >>> 2;
		int high = max;
		if(high == 0) return;
		int low = 0;
		do {
			int p = (low + high) >>> 1;
			final int cmp = id.prefixCompare(data, idOffset(p));
			if(cmp < 0)
				high = p;
			else if(cmp == 0) {
				while(0 < p && id.prefixCompare(data, idOffset(p - 1)) == 0)
					p--;
				for(; p < max && id.prefixCompare(data, idOffset(p)) == 0; p++) {
					matches.add(ObjectId.fromRaw(data, idOffset(p)));
					if(matches.size() > matchLimit)
						break;
				}
				return;
			} else
				low = p + 1;
		} while(low < high);
	}

	private static int idOffset(int p) {
		return (p << 2) + p;
	}

	private int binarySearchLevelTwo(AnyObjectId objId, int levelOne) {
		final int[] data = names[levelOne];
		int high = offset32[levelOne].length >>> 2;
		if(high == 0)
			return -1;
		int low = 0;
		do {
			final int mid = (low + high) >>> 1;
			final int mid4 = mid << 2;
			final int cmp;

			cmp = objId.compareTo(data, mid4 + mid);
			if(cmp < 0)
				high = mid;
			else if(cmp == 0) {
				return mid;
			} else
				low = mid + 1;
		} while(low < high);
		return -1;
	}

	private class EntriesIteratorV2 extends EntriesIterator {
		int levelOne;

		int levelTwo;

		@Override
		protected MutableEntry initEntry() {
			return new MutableEntry() {
				@Override
				protected void ensureId() {
					idBuffer.fromRaw(names[levelOne], levelTwo
							- Constants.OBJECT_ID_LENGTH / 4);
				}
			};
		}

		@Override
		public MutableEntry next() {
			for(; levelOne < names.length; levelOne++) {
				if(levelTwo < names[levelOne].length) {
					int idx = levelTwo / (Constants.OBJECT_ID_LENGTH / 4) * 4;
					long offset = NB.decodeUInt32(offset32[levelOne], idx);
					if((offset & IS_O64) != 0) {
						idx = (8 * (int) (offset & ~IS_O64));
						offset = NB.decodeUInt64(offset64, idx);
					}
					entry.offset = offset;

					levelTwo += Constants.OBJECT_ID_LENGTH / 4;
					returnedNumber++;
					return entry;
				}
				levelTwo = 0;
			}
			throw new NoSuchElementException();
		}
	}

}
