/*
 * Copyright (C) 2009, Google Inc.
 * Copyright (C) 2008, Marek Zawirski <marek.zawirski@gmail.com>
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.lib;

import java.util.Iterator;
import java.util.NoSuchElementException;

public class ObjectIdSubclassMap<V extends ObjectId>
		implements Iterable<V>, ObjectIdSet {
	private static final int INITIAL_TABLE_SIZE = 2048;
	int size;
	private int grow;
	private int mask;
	V[] table;

	public ObjectIdSubclassMap() {
		initTable(INITIAL_TABLE_SIZE);
	}

	public void clear() {
		size = 0;
		initTable(INITIAL_TABLE_SIZE);
	}

	public V get(AnyObjectId toFind) {
		final int msk = mask;
		int i = toFind.w1 & msk;
		final V[] tbl = table;
		V obj;

		while ((obj = tbl[i]) != null) {
			if (AnyObjectId.isEqual(obj, toFind)) {
				return obj;
			}
			i = (i + 1) & msk;
		}
		return null;
	}

	@Override
	public boolean contains(AnyObjectId toFind) {
		return get(toFind) != null;
	}

	public <Q extends V> void add(Q newValue) {
		if (++size == grow)
			grow();
		insert(newValue);
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

			private int i;

			@Override
			public boolean hasNext() {
				return found < size;
			}

			@Override
			public V next() {
				while (i < table.length) {
					final V v = table[i++];
					if (v != null) {
						found++;
						return v;
					}
				}
				throw new NoSuchElementException();
			}

			@Override
			public void remove() {
				throw new UnsupportedOperationException();
			}
		};
	}

	private void insert(V newValue) {
		final int msk = mask;
		int j = newValue.w1 & msk;
		final V[] tbl = table;
		while (tbl[j] != null)
			j = (j + 1) & msk;
		tbl[j] = newValue;
	}

	private void grow() {
		final V[] oldTable = table;
		final int oldSize = table.length;

		initTable(oldSize << 1);
		for (int i = 0; i < oldSize; i++) {
			final V obj = oldTable[i];
			if (obj != null)
				insert(obj);
		}
	}

	private void initTable(int sz) {
		grow = sz >> 1;
		mask = sz - 1;
		table = createArray(sz);
	}

	@SuppressWarnings("unchecked")
	private V[] createArray(int sz) {
		return (V[]) new ObjectId[sz];
	}
}
