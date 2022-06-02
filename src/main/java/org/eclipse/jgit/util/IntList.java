/*
 * Copyright (C) 2008, Google Inc.
 * Copyright (C) 2009, Johannes Schindelin <johannes.schindelin@gmx.de> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.util;

public class IntList {

	private int[] entries;
	private int count;

	public IntList() {
		this(10);
	}

	public IntList(int capacity) {
		entries = new int[capacity];
	}

	public int size() {
		return count;
	}

	public int get(int i) {
		if(count <= i)
			throw new ArrayIndexOutOfBoundsException(i);
		return entries[i];
	}

	public void clear() {
		count = 0;
	}

	public void add(int n) {
		if(count == entries.length)
			grow();
		entries[count++] = n;
	}

	public void set(int index, int n) {
		if(count < index)
			throw new ArrayIndexOutOfBoundsException(index);
		else if(count == index)
			add(n);
		else
			entries[index] = n;
	}

	public void fillTo(int toIndex, int val) {
		while(count < toIndex)
			add(val);
	}

	private void grow() {
		final int[] n = new int[(entries.length + 16) * 3 / 2];
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
