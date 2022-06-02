/*
 * Copyright (C) 2008-2011, Google Inc.
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.dfs;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.LongStream;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.internal.storage.pack.PackExt;

public final class DfsBlockCache {
	private static volatile DfsBlockCache cache;

	static {
		reconfigure(new DfsBlockCacheConfig());
	}

	public static void reconfigure(DfsBlockCacheConfig cfg) {
		cache = new DfsBlockCache(cfg);
	}

	public static DfsBlockCache getInstance() {
		return cache;
	}

	private final int tableSize;
	private final AtomicReferenceArray<HashEntry> table;
	private final ReentrantLock[] loadLocks;
	private final ReentrantLock[][] refLocks;
	private final long maxBytes;
	private final long maxStreamThroughCache;
	private final int blockSize;
	private final int blockSizeShift;
	private final AtomicReference<AtomicLong[]> statHit;
	private final AtomicReference<AtomicLong[]> statMiss;
	private final AtomicReference<AtomicLong[]> statEvict;
	private final AtomicReference<AtomicLong[]> liveBytes;
	private final ReentrantLock clockLock;
	private final Consumer<Long> refLockWaitTime;

	private Ref clockHand;
	private final int[] cacheHotLimits = new int[PackExt.values().length];
	private final DfsBlockCacheConfig.IndexEventConsumer indexEventConsumer;
	private final Map<EvictKey, Long> indexEvictionMap = new ConcurrentHashMap<>();

	private DfsBlockCache(DfsBlockCacheConfig cfg) {
		tableSize = tableSize(cfg);
		if (tableSize < 1) {
			throw new IllegalArgumentException(JGitText.get().tSizeMustBeGreaterOrEqual1);
		}

		table = new AtomicReferenceArray<>(tableSize);
		int concurrencyLevel = cfg.getConcurrencyLevel();
		loadLocks = new ReentrantLock[concurrencyLevel];
		for (int i = 0; i < loadLocks.length; i++) {
			loadLocks[i] = new ReentrantLock(true);
		}
		refLocks = new ReentrantLock[PackExt.values().length][concurrencyLevel];
		for (int i = 0; i < PackExt.values().length; i++) {
			for (int j = 0; j < concurrencyLevel; ++j) {
				refLocks[i][j] = new ReentrantLock(true);
			}
		}

		maxBytes = cfg.getBlockLimit();
		maxStreamThroughCache = (long) (maxBytes * cfg.getStreamRatio());
		blockSize = cfg.getBlockSize();
		blockSizeShift = Integer.numberOfTrailingZeros(blockSize);

		clockLock = new ReentrantLock(true);
		String none = ""; 
		clockHand = new Ref<>(
				DfsStreamKey.of(new DfsRepositoryDescription(none), none, null),
				-1, 0, null);
		clockHand.next = clockHand;

		statHit = new AtomicReference<>(newCounters());
		statMiss = new AtomicReference<>(newCounters());
		statEvict = new AtomicReference<>(newCounters());
		liveBytes = new AtomicReference<>(newCounters());

		refLockWaitTime = cfg.getRefLockWaitTimeConsumer();

		for (int i = 0; i < PackExt.values().length; ++i) {
			Integer limit = cfg.getCacheHotMap().get(PackExt.values()[i]);
			if (limit != null && limit > 0) {
				cacheHotLimits[i] = limit;
			} else {
				cacheHotLimits[i] = DfsBlockCacheConfig.DEFAULT_CACHE_HOT_MAX;
			}
		}
		indexEventConsumer = cfg.getIndexEventConsumer();
	}

	boolean shouldCopyThroughCache(long length) {
		return length <= maxStreamThroughCache;
	}

	public long[] getCurrentSize() {
		return getStatVals(liveBytes);
	}

	private int hash(int packHash, long off) {
		return packHash + (int) (off >>> blockSizeShift);
	}

	int getBlockSize() {
		return blockSize;
	}

	private static int tableSize(DfsBlockCacheConfig cfg) {
		final int wsz = cfg.getBlockSize();
		final long limit = cfg.getBlockLimit();
		if (wsz <= 0) {
			throw new IllegalArgumentException(JGitText.get().invalidWindowSize);
		}
		if (limit < wsz) {
			throw new IllegalArgumentException(JGitText.get().windowSizeMustBeLesserThanLimit);
		}
		return (int) Math.min(5 * (limit / wsz) / 2, Integer.MAX_VALUE);
	}

	DfsBlock getOrLoad(BlockBasedFile file, long position,
					   ReadableChannelSupplier fileChannel) throws IOException {
		final long requestedPosition = position;
		position = file.alignToBlock(position);

		DfsStreamKey key = file.key;
		int slot = slot(key, position);
		HashEntry e1 = table.get(slot);
		DfsBlock v = scan(e1, key, position);
		if (v != null && v.contains(key, requestedPosition)) {
			getStat(statHit, key).incrementAndGet();
			return v;
		}

		reserveSpace(blockSize, key);
		ReentrantLock regionLock = lockFor(key, position);
		regionLock.lock();
		try {
			HashEntry e2 = table.get(slot);
			if (e2 != e1) {
				v = scan(e2, key, position);
				if (v != null) {
					getStat(statHit, key).incrementAndGet();
					creditSpace(blockSize, key);
					return v;
				}
			}

			getStat(statMiss, key).incrementAndGet();
			boolean credit = true;
			try {
				v = file.readOneBlock(position, fileChannel.get());
				credit = false;
			} finally {
				if (credit) {
					creditSpace(blockSize, key);
				}
			}
			if (position != v.start) {
				position = v.start;
				slot = slot(key, position);
				e2 = table.get(slot);
			}

			Ref<DfsBlock> ref = new Ref<>(key, position, v.size(), v);
			ref.markHotter();
			for (;;) {
				HashEntry n = new HashEntry(clean(e2), ref);
				if (table.compareAndSet(slot, e2, n)) {
					break;
				}
				e2 = table.get(slot);
			}
			addToClock(ref, blockSize - v.size());
		} finally {
			regionLock.unlock();
		}

		if (v.contains(file.key, requestedPosition)) {
			return v;
		}
		return getOrLoad(file, requestedPosition, fileChannel);
	}

	private void reserveSpace(long reserve, DfsStreamKey key) {
		clockLock.lock();
		try {
			long live = LongStream.of(getCurrentSize()).sum() + reserve;
			if (maxBytes < live) {
				Ref prev = clockHand;
				Ref hand = clockHand.next;
				do {
					if (hand.isHot()) {
						hand.markColder();
						prev = hand;
						hand = hand.next;
						continue;
					} else if (prev == hand)
						break;

					Ref dead = hand;
					hand = hand.next;
					prev.next = hand;
					dead.next = null;
					dead.value = null;
					live -= dead.size;
					getStat(liveBytes, dead.key).addAndGet(-dead.size);
					getStat(statEvict, dead.key).incrementAndGet();
					reportIndexEvicted(dead);
				} while (maxBytes < live);
				clockHand = prev;
			}
			getStat(liveBytes, key).addAndGet(reserve);
		} finally {
			clockLock.unlock();
		}
	}

	private void creditSpace(long credit, DfsStreamKey key) {
		clockLock.lock();
		try {
			getStat(liveBytes, key).addAndGet(-credit);
		} finally {
			clockLock.unlock();
		}
	}

	private void addToClock(Ref ref, long credit) {
		clockLock.lock();
		try {
			if (credit != 0) {
				getStat(liveBytes, ref.key).addAndGet(-credit);
			}
			Ref ptr = clockHand;
			ref.next = ptr.next;
			ptr.next = ref;
			clockHand = ref;
		} finally {
			clockLock.unlock();
		}
	}

	void put(DfsBlock v) {
		put(v.stream, v.start, v.size(), v);
	}

	<T> Ref<T> getOrLoadRef(
			DfsStreamKey key, long position, RefLoader<T> loader)
			throws IOException {
		long start = System.nanoTime();
		int slot = slot(key, position);
		HashEntry e1 = table.get(slot);
		Ref<T> ref = scanRef(e1, key, position);
		if (ref != null) {
			getStat(statHit, key).incrementAndGet();
			reportIndexRequested(ref, true, start);
			return ref;
		}

		ReentrantLock regionLock = lockForRef(key);
		long lockStart = System.currentTimeMillis();
		regionLock.lock();
		try {
			HashEntry e2 = table.get(slot);
			if (e2 != e1) {
				ref = scanRef(e2, key, position);
				if (ref != null) {
					getStat(statHit, key).incrementAndGet();
					reportIndexRequested(ref, true,
							start);
					return ref;
				}
			}

			if (refLockWaitTime != null) {
				refLockWaitTime.accept(System.currentTimeMillis() - lockStart);
			}
			getStat(statMiss, key).incrementAndGet();
			ref = loader.load();
			ref.markHotter();
			reserveSpace(ref.size, key);
			for (;;) {
				HashEntry n = new HashEntry(clean(e2), ref);
				if (table.compareAndSet(slot, e2, n)) {
					break;
				}
				e2 = table.get(slot);
			}
			addToClock(ref, 0);
		} finally {
			regionLock.unlock();
		}
		reportIndexRequested(ref, false, start);
		return ref;
	}

	<T> Ref<T> putRef(DfsStreamKey key, long size, T v) {
		return put(key, 0, size, v);
	}

	<T> Ref<T> put(DfsStreamKey key, long pos, long size, T v) {
		int slot = slot(key, pos);
		HashEntry e1 = table.get(slot);
		Ref<T> ref = scanRef(e1, key, pos);
		if (ref != null) {
			return ref;
		}

		reserveSpace(size, key);
		ReentrantLock regionLock = lockFor(key, pos);
		regionLock.lock();
		try {
			HashEntry e2 = table.get(slot);
			if (e2 != e1) {
				ref = scanRef(e2, key, pos);
				if (ref != null) {
					creditSpace(size, key);
					return ref;
				}
			}

			ref = new Ref<>(key, pos, size, v);
			ref.markHotter();
			for (;;) {
				HashEntry n = new HashEntry(clean(e2), ref);
				if (table.compareAndSet(slot, e2, n)) {
					break;
				}
				e2 = table.get(slot);
			}
			addToClock(ref, 0);
		} finally {
			regionLock.unlock();
		}
		return ref;
	}

	<T> T get(DfsStreamKey key, long position) {
		T val = scan(table.get(slot(key, position)), key, position);
		if (val == null) {
			getStat(statMiss, key).incrementAndGet();
		} else {
			getStat(statHit, key).incrementAndGet();
		}
		return val;
	}

	private <T> T scan(HashEntry n, DfsStreamKey key, long position) {
		Ref<T> r = scanRef(n, key, position);
		return r != null ? r.get() : null;
	}

	@SuppressWarnings("unchecked")
	private <T> Ref<T> scanRef(HashEntry n, DfsStreamKey key, long position) {
		for (; n != null; n = n.next) {
			Ref<T> r = n.ref;
			if (r.position == position && r.key.equals(key)) {
				return r.get() != null ? r : null;
			}
		}
		return null;
	}

	private int slot(DfsStreamKey key, long position) {
		return (hash(key.hash, position) >>> 1) % tableSize;
	}

	private ReentrantLock lockFor(DfsStreamKey key, long position) {
		return loadLocks[(hash(key.hash, position) >>> 1) % loadLocks.length];
	}

	private ReentrantLock lockForRef(DfsStreamKey key) {
		int slot = (key.hash >>> 1) % refLocks[key.packExtPos].length;
		return refLocks[key.packExtPos][slot];
	}

	private static AtomicLong[] newCounters() {
		AtomicLong[] ret = new AtomicLong[PackExt.values().length];
		for (int i = 0; i < ret.length; i++) {
			ret[i] = new AtomicLong();
		}
		return ret;
	}

	private static AtomicLong getStat(AtomicReference<AtomicLong[]> stats,
			DfsStreamKey key) {
		int pos = key.packExtPos;
		while (true) {
			AtomicLong[] vals = stats.get();
			if (pos < vals.length) {
				return vals[pos];
			}
			AtomicLong[] expect = vals;
			vals = new AtomicLong[Math.max(pos + 1, PackExt.values().length)];
			System.arraycopy(expect, 0, vals, 0, expect.length);
			for (int i = expect.length; i < vals.length; i++) {
				vals[i] = new AtomicLong();
			}
			if (stats.compareAndSet(expect, vals)) {
				return vals[pos];
			}
		}
	}

	private static long[] getStatVals(AtomicReference<AtomicLong[]> stat) {
		AtomicLong[] stats = stat.get();
		long[] cnt = new long[stats.length];
		for (int i = 0; i < stats.length; i++) {
			cnt[i] = stats[i].get();
		}
		return cnt;
	}

	private static HashEntry clean(HashEntry top) {
		while (top != null && top.ref.next == null) {
			top = top.next;
		}
		if (top == null) {
			return null;
		}
		HashEntry n = clean(top.next);
		return n == top.next ? top : new HashEntry(n, top.ref);
	}

	private void reportIndexRequested(Ref<?> ref, boolean cacheHit,
			long start) {
		if (indexEventConsumer == null
				|| !isIndexOrBitmapExtPos(ref.key.packExtPos)) {
			return;
		}
		EvictKey evictKey = new EvictKey(ref);
		Long prevEvictedTime = indexEvictionMap.get(evictKey);
		long now = System.nanoTime();
		long sinceLastEvictionNanos = prevEvictedTime == null ? 0L : now - prevEvictedTime;
		indexEventConsumer.acceptRequestedEvent(ref.key.packExtPos, cacheHit,
				(now - start) / 1000L , ref.size,
				Duration.ofNanos(sinceLastEvictionNanos));
	}

	private void reportIndexEvicted(Ref<?> dead) {
		if (indexEventConsumer == null
				|| !indexEventConsumer.shouldReportEvictedEvent()
				|| !isIndexOrBitmapExtPos(dead.key.packExtPos)) {
			return;
		}
		EvictKey evictKey = new EvictKey(dead);
		long now = System.nanoTime();
		indexEvictionMap.put(evictKey, now);
		indexEventConsumer.acceptEvictedEvent(dead.totalHitCount.get());
	}

	private static boolean isIndexOrBitmapExtPos(int packExtPos) {
		return packExtPos == PackExt.INDEX.getPosition()
				|| packExtPos == PackExt.BITMAP_INDEX.getPosition();
	}

	private static final class HashEntry {

		final HashEntry next;
		final Ref ref;

		HashEntry(HashEntry n, Ref r) {
			next = n;
			ref = r;
		}
	}

	static final class Ref<T> {
		final DfsStreamKey key;
		final long position;
		final long size;
		volatile T value;
		Ref next;

		private volatile int hotCount;
		private final AtomicInteger totalHitCount = new AtomicInteger();

		Ref(DfsStreamKey key, long position, long size, T v) {
			this.key = key;
			this.position = position;
			this.size = size;
			this.value = v;
		}

		T get() {
			T v = value;
			if (v != null) {
				markHotter();
			}
			return v;
		}

		void markHotter() {
			int cap = DfsBlockCache
					.getInstance().cacheHotLimits[key.packExtPos];
			hotCount = Math.min(cap, hotCount + 1);
			totalHitCount.incrementAndGet();
		}

		void markColder() {
			hotCount = Math.max(0, hotCount - 1);
		}

		boolean isHot() {
			return hotCount > 0;
		}
	}

	private static final class EvictKey {
		private final int keyHash;
		private final int packExtPos;
		private final long position;

		EvictKey(Ref<?> ref) {
			keyHash = ref.key.hash;
			packExtPos = ref.key.packExtPos;
			position = ref.position;
		}

		@Override
		public boolean equals(Object object) {
			if (object instanceof EvictKey) {
				EvictKey other = (EvictKey) object;
				return keyHash == other.keyHash
						&& packExtPos == other.packExtPos
						&& position == other.position;
			}
			return false;
		}

		@Override
		public int hashCode() {
			return DfsBlockCache.getInstance().hash(keyHash, position);
		}
	}

	@FunctionalInterface
	interface RefLoader<T> {
		Ref<T> load() throws IOException;
	}

	@FunctionalInterface
	interface ReadableChannelSupplier {

		ReadableChannel get() throws IOException;
	}
}