/*
 * Copyright (C) 2019, Marc Strapetz <marc.strapetz@syntevo.com>
 * Copyright (C) 2019, Matthias Sohn <matthias.sohn@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.util;

import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.internal.JGitText;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class SimpleLruCache<K, V> {

	private static class Entry<K, V> {

		private final K key;
		private final V value;
		private volatile long lastAccessed;
		private long lastAccessedSorting;

		Entry(K key, V value, long lastAccessed) {
			this.key = key;
			this.value = value;
			this.lastAccessed = lastAccessed;
		}

		void copyAccessTime() {
			lastAccessedSorting = lastAccessed;
		}

		@SuppressWarnings("nls")
		@Override
		public String toString() {
			return "Entry [lastAccessed=" + lastAccessed + ", key=" + key
					+ ", value=" + value + "]";
		}
	}

	private final Lock lock = new ReentrantLock();

	private final Map<K, Entry<K, V>> map = new ConcurrentHashMap<>();

	private final int maximumSize;

	private final int purgeSize;

	private volatile long time = 0L;

	private static void checkPurgeFactor(float purgeFactor) {
		if(purgeFactor <= 0 || purgeFactor >= 1) {
			throw new IllegalArgumentException(
					MessageFormat.format(JGitText.get().invalidPurgeFactor,
							purgeFactor));
		}
	}

	private static int purgeSize(int maxSize, float purgeFactor) {
		return (int) ((1 - purgeFactor) * maxSize);
	}

	public SimpleLruCache(int maxSize, float purgeFactor) {
		checkPurgeFactor(purgeFactor);
		this.maximumSize = maxSize;
		this.purgeSize = purgeSize(maxSize, purgeFactor);
	}

	@SuppressWarnings("NonAtomicVolatileUpdate")
	public V get(Object key) {
		Entry<K, V> entry = map.get(key);
		if(entry != null) {
			entry.lastAccessed = tick();
			return entry.value;
		}
		return null;
	}

	@SuppressWarnings("NonAtomicVolatileUpdate")
	public V put(@NonNull K key, @NonNull V value) {
		map.put(key, new Entry<>(key, value, tick()));
		if(map.size() > maximumSize) {
			purge();
		}
		return value;
	}

	@SuppressWarnings("NonAtomicVolatileUpdate")
	private long tick() {
		return ++time;
	}

	private void purge() {
		if(lock.tryLock()) {
			try {
				List<Entry> entriesToPurge = new ArrayList<>(map.values());
				for(Entry e : entriesToPurge) {
					e.copyAccessTime();
				}
				entriesToPurge.sort(Comparator.comparingLong(o -> -o.lastAccessedSorting));
				for(int index = purgeSize; index < entriesToPurge
						.size(); index++) {
					map.remove(entriesToPurge.get(index).key);
				}
			} finally {
				lock.unlock();
			}
		}
	}
}
