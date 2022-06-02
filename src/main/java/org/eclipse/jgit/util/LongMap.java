/*
 * Copyright (C) 2009, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.util;

public class LongMap<V> {
	private static final float LOAD_FACTOR = 0.75f;

	private Node<V>[] table;
	private int size;
	private int growAt;

	public LongMap() {
		table = createArray(64);
		growAt = (int) (table.length * LOAD_FACTOR);
	}

	public V get(long key) {
		for(Node<V> n = table[index(key)]; n != null; n = n.next) {
			if(n.key == key)
				return n.value;
		}
		return null;
	}

	public V remove(long key) {
		Node<V> n = table[index(key)];
		Node<V> prior = null;
		while(n != null) {
			if(n.key == key) {
				if(prior == null)
					table[index(key)] = n.next;
				else
					prior.next = n.next;
				size--;
				return n.value;
			}
			prior = n;
			n = n.next;
		}
		return null;
	}

	public V put(long key, V value) {
		for(Node<V> n = table[index(key)]; n != null; n = n.next) {
			if(n.key == key) {
				final V o = n.value;
				n.value = value;
				return o;
			}
		}

		if(++size == growAt)
			grow();
		insert(new Node<>(key, value));
		return null;
	}

	private void insert(Node<V> n) {
		final int idx = index(n.key);
		n.next = table[idx];
		table[idx] = n;
	}

	private void grow() {
		final Node<V>[] oldTable = table;
		final int oldSize = table.length;

		table = createArray(oldSize << 1);
		growAt = (int) (table.length * LOAD_FACTOR);
		for(int i = 0; i < oldSize; i++) {
			Node<V> e = oldTable[i];
			while(e != null) {
				final Node<V> n = e.next;
				insert(e);
				e = n;
			}
		}
	}

	private int index(long key) {
		int h = ((int) key) >>> 1;
		h ^= (h >>> 20) ^ (h >>> 12);
		return h & (table.length - 1);
	}

	@SuppressWarnings("unchecked")
	private static <V> Node<V>[] createArray(int sz) {
		return new Node[sz];
	}

	private static class Node<V> {
		final long key;
		V value;
		Node<V> next;

		Node(long k, V v) {
			key = k;
			value = v;
		}
	}
}
