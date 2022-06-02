/*
 * Copyright (C) 2011, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.lib;

import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;

public class ObjectIdOwnerMap<V extends ObjectIdOwnerMap.Entry>
		implements Iterable<V>, ObjectIdSet {

	private static final int INITIAL_DIRECTORY = 1024;
	private static final int SEGMENT_BITS = 11;
	private static final int SEGMENT_SHIFT = 32 - SEGMENT_BITS;

	V[][] directory;
	int size;
	private int grow;
	int bits;
	private int mask;

	@SuppressWarnings("unchecked")
	public ObjectIdOwnerMap() {
		bits = 0;
		mask = 0;
		grow = computeGrowAt(bits);

		directory = (V[][]) new Entry[INITIAL_DIRECTORY][];
		directory[0] = newSegment();
	}

	public void clear() {
		size = 0;

		for(V[] tbl : directory) {
			if(tbl == null)
				break;
			Arrays.fill(tbl, null);
		}
	}

	@SuppressWarnings("unchecked")
	public V get(AnyObjectId toFind) {
		if(toFind == null) {
			return null;
		}
		int h = toFind.w1;
		V obj = directory[h & mask][h >>> SEGMENT_SHIFT];
		for(; obj != null; obj = (V) obj.next)
			if(equals(obj, toFind))
				return obj;
		return null;
	}

	@Override
	public boolean contains(AnyObjectId toFind) {
		return get(toFind) != null;
	}

	public <Q extends V> void add(Q newValue) {
		if(++size == grow)
			grow();

		int h = newValue.w1;
		V[] table = directory[h & mask];
		h >>>= SEGMENT_SHIFT;

		newValue.next = table[h];
		table[h] = newValue;
	}

	@SuppressWarnings("unchecked")
	public <Q extends V> V addIfAbsent(Q newValue) {
		int h = newValue.w1;
		V[] table = directory[h & mask];
		h >>>= SEGMENT_SHIFT;

		for(V obj = table[h]; obj != null; obj = (V) obj.next)
			if(equals(obj, newValue))
				return obj;

		newValue.next = table[h];
		table[h] = newValue;

		if(++size == grow)
			grow();
		return newValue;
	}

	public int size() {
		return size;
	}

	public boolean isEmpty() {
		return size == 0;
	}

	@Override
	public Iterator<V> iterator() {
		return new Iterator<V>() {
			private int found;
			private int dirIdx;
			private int tblIdx;
			private V next;

			@Override
			public boolean hasNext() {
				return found < size;
			}

			@Override
			public V next() {
				if(next != null)
					return found(next);

				for(; ; ) {
					V[] table = directory[dirIdx];
					if(tblIdx == table.length) {
						if(++dirIdx >= (1 << bits))
							throw new NoSuchElementException();
						table = directory[dirIdx];
						tblIdx = 0;
					}

					while(tblIdx < table.length) {
						V v = table[tblIdx++];
						if(v != null)
							return found(v);
					}
				}
			}

			@SuppressWarnings("unchecked")
			private V found(V v) {
				found++;
				next = (V) v.next;
				return v;
			}

			@Override
			public void remove() {
				throw new UnsupportedOperationException();
			}
		};
	}

	@SuppressWarnings("unchecked")
	private void grow() {
		final int oldDirLen = 1 << bits;
		final int s = 1 << bits;

		bits++;
		mask = (1 << bits) - 1;
		grow = computeGrowAt(bits);

		final int newDirLen = 1 << bits;
		if(directory.length < newDirLen) {
			V[][] newDir = (V[][]) new Entry[newDirLen << 1][];
			System.arraycopy(directory, 0, newDir, 0, oldDirLen);
			directory = newDir;
		}

		for(int dirIdx = 0; dirIdx < oldDirLen; dirIdx++) {
			final V[] oldTable = directory[dirIdx];
			final V[] newTable = newSegment();

			for(int i = 0; i < oldTable.length; i++) {
				V chain0 = null;
				V chain1 = null;
				V next;

				for(V obj = oldTable[i]; obj != null; obj = next) {
					next = (V) obj.next;

					if((obj.w1 & s) == 0) {
						obj.next = chain0;
						chain0 = obj;
					} else {
						obj.next = chain1;
						chain1 = obj;
					}
				}

				oldTable[i] = chain0;
				newTable[i] = chain1;
			}

			directory[oldDirLen + dirIdx] = newTable;
		}
	}

	@SuppressWarnings("unchecked")
	private V[] newSegment() {
		return (V[]) new Entry[1 << SEGMENT_BITS];
	}

	private static int computeGrowAt(int bits) {
		return 1 << (bits + SEGMENT_BITS);
	}

	private static boolean equals(AnyObjectId firstObjectId,
								  AnyObjectId secondObjectId) {
		return firstObjectId.w2 == secondObjectId.w2
				&& firstObjectId.w3 == secondObjectId.w3
				&& firstObjectId.w4 == secondObjectId.w4
				&& firstObjectId.w5 == secondObjectId.w5
				&& firstObjectId.w1 == secondObjectId.w1;
	}

	public abstract static class Entry extends ObjectId {
		transient Entry next;

		public Entry(AnyObjectId id) {
			super(id);
		}
	}
}
