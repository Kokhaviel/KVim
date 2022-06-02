/*
 * Copyright (C) 2009, Christian Halstrick <christian.halstrick@sap.com>
 * Copyright (C) 2009, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.util;

public class LongList {

	private long[] entries;
	private int count;

	public LongList() {
		this(10);
	}

	public LongList(int capacity) {
		entries = new long[capacity];
	}

	public int size() {
		return count;
	}

	public long get(int i) {
		if(count <= i)
			throw new ArrayIndexOutOfBoundsException(i);
		return entries[i];
	}

	public boolean contains(long value) {
		for(int i = 0; i < count; i++)
			if(entries[i] == value)
				return true;
		return false;
	}

	public void clear() {
		count = 0;
	}

	public void add(long n) {
		if(count == entries.length)
			grow();
		entries[count++] = n;
	}

	public void set(int index, long n) {
		if(count < index)
			throw new ArrayIndexOutOfBoundsException(index);
		else if(count == index)
			add(n);
		else
			entries[index] = n;
	}

	private void grow() {
		final long[] n = new long[(entries.length + 16) * 3 / 2];
		System.arraycopy(entries, 0, n, 0, count);
		entries = n;
	}

	@Override
	public String toString() {
		final StringBuilder r = new StringBuilder();
		r.append('[');
		for(int i = 0; i < count; i++) {
			if(i > 0)
				r.append(", ");
			r.append(entries[i]);
		}
		r.append(']');
		return r.toString();
	}
}
