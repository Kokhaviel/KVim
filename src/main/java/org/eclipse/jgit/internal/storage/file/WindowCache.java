/*
 * Copyright (C) 2008, 2009 Google Inc.
 * Copyright (C) 2008, 2020 Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.file;

import java.io.IOException;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.storage.file.WindowCacheConfig;
import org.eclipse.jgit.storage.file.WindowCacheStats;
import org.eclipse.jgit.util.Monitoring;

public class WindowCache {

	interface StatsRecorder {

		void recordHits(int count);

		void recordMisses(int count);

		void recordLoadSuccess(long loadTimeNanos);

		void recordLoadFailure(long loadTimeNanos);

		void recordEvictions(int count);

		void recordOpenFiles(int delta);

		void recordOpenBytes(Pack pack, int delta);

		@NonNull
		WindowCacheStats getStats();
	}

	static class StatsRecorderImpl
			implements StatsRecorder, WindowCacheStats {
		private final LongAdder hitCount;
		private final LongAdder missCount;
		private final LongAdder loadSuccessCount;
		private final LongAdder loadFailureCount;
		private final LongAdder totalLoadTime;
		private final LongAdder evictionCount;
		private final LongAdder openFileCount;
		private final LongAdder openByteCount;
		private final Map<String, LongAdder> openByteCountPerRepository;

		public StatsRecorderImpl() {
			hitCount = new LongAdder();
			missCount = new LongAdder();
			loadSuccessCount = new LongAdder();
			loadFailureCount = new LongAdder();
			totalLoadTime = new LongAdder();
			evictionCount = new LongAdder();
			openFileCount = new LongAdder();
			openByteCount = new LongAdder();
			openByteCountPerRepository = new ConcurrentHashMap<>();
		}

		@Override
		public void recordHits(int count) {
			hitCount.add(count);
		}

		@Override
		public void recordMisses(int count) {
			missCount.add(count);
		}

		@Override
		public void recordLoadSuccess(long loadTimeNanos) {
			loadSuccessCount.increment();
			totalLoadTime.add(loadTimeNanos);
		}

		@Override
		public void recordLoadFailure(long loadTimeNanos) {
			loadFailureCount.increment();
			totalLoadTime.add(loadTimeNanos);
		}

		@Override
		public void recordEvictions(int count) {
			evictionCount.add(count);
		}

		@Override
		public void recordOpenFiles(int delta) {
			openFileCount.add(delta);
		}

		@Override
		public void recordOpenBytes(Pack pack, int delta) {
			openByteCount.add(delta);
			String repositoryId = repositoryId(pack);
			LongAdder la = openByteCountPerRepository
					.computeIfAbsent(repositoryId, k -> new LongAdder());
			la.add(delta);
			if(delta < 0) {
				openByteCountPerRepository.computeIfPresent(repositoryId,
						(k, v) -> v.longValue() == 0 ? null : v);
			}
		}

		private static String repositoryId(Pack pack) {
			return pack.getPackFile().getParentFile().getParentFile()
					.getParent();
		}

		@Override
		public WindowCacheStats getStats() {
			return this;
		}

		@Override
		public long getOpenFileCount() {
			return openFileCount.sum();
		}

		@Override
		public long getOpenByteCount() {
			return openByteCount.sum();
		}

	}

	private static int bits(int newSize) {
		if(newSize < 4096)
			throw new IllegalArgumentException(JGitText.get().invalidWindowSize);
		if(Integer.bitCount(newSize) != 1)
			throw new IllegalArgumentException(JGitText.get().windowSizeMustBePowerOf2);
		return Integer.numberOfTrailingZeros(newSize);
	}

	private static final Random rng = new Random();

	private static volatile WindowCache cache;

	private static volatile int streamFileThreshold;

	static {
		reconfigure(new WindowCacheConfig());
	}

	public static void reconfigure(WindowCacheConfig cfg) {
		final WindowCache nc = new WindowCache(cfg);
		final WindowCache oc = cache;
		if(oc != null)
			oc.removeAll();
		cache = nc;
		streamFileThreshold = cfg.getStreamFileThreshold();
		DeltaBaseCache.reconfigure(cfg);
	}

	static int getStreamFileThreshold() {
		return streamFileThreshold;
	}

	public static WindowCache getInstance() {
		return cache.publishMBeanIfNeeded();
	}

	static ByteWindow get(Pack pack, long offset)
			throws IOException {
		final WindowCache c = cache;
		final ByteWindow r = c.getOrLoad(pack, c.toStart(offset));
		if(c != cache.publishMBeanIfNeeded()) {
			c.removeAll();
		}
		return r;
	}

	static void purge(Pack pack) {
		cache.removeAll(pack);
	}

	private final CleanupQueue queue;
	private final int tableSize;
	private final AtomicLong clock;
	private final AtomicReferenceArray<Entry> table;
	private final Lock[] locks;
	private final ReentrantLock evictLock;
	private final int evictBatch;
	private final int maxFiles;
	private final long maxBytes;
	private final boolean mmap;
	private final int windowSizeShift;
	private final int windowSize;
	private final StatsRecorder statsRecorder;
	private final StatsRecorderImpl mbean;
	private final AtomicBoolean publishMBean = new AtomicBoolean();
	private final boolean useStrongRefs;

	private WindowCache(WindowCacheConfig cfg) {
		tableSize = tableSize(cfg);
		final int lockCount = lockCount(cfg);
		if(tableSize < 1)
			throw new IllegalArgumentException(JGitText.get().tSizeMustBeGreaterOrEqual1);
		if(lockCount < 1)
			throw new IllegalArgumentException(JGitText.get().lockCountMustBeGreaterOrEqual1);

		clock = new AtomicLong(1);
		table = new AtomicReferenceArray<>(tableSize);
		locks = new Lock[lockCount];
		for(int i = 0; i < locks.length; i++)
			locks[i] = new Lock();
		evictLock = new ReentrantLock();

		int eb = (int) (tableSize * .1);
		if(64 < eb)
			eb = 64;
		else if(eb < 4)
			eb = 4;
		if(tableSize < eb)
			eb = tableSize;
		evictBatch = eb;

		maxFiles = cfg.getPackedGitOpenFiles();
		maxBytes = cfg.getPackedGitLimit();
		mmap = cfg.isPackedGitMMAP();
		windowSizeShift = bits(cfg.getPackedGitWindowSize());
		windowSize = 1 << windowSizeShift;
		useStrongRefs = cfg.isPackedGitUseStrongRefs();
		queue = useStrongRefs ? new StrongCleanupQueue(this)
				: new SoftCleanupQueue(this);

		mbean = new StatsRecorderImpl();
		statsRecorder = mbean;
		publishMBean.set(cfg.getExposeStatsViaJmx());

		if(maxFiles < 1)
			throw new IllegalArgumentException(JGitText.get().openFilesMustBeAtLeast1);
		if(maxBytes < windowSize)
			throw new IllegalArgumentException(JGitText.get().windowSizeMustBeLesserThanLimit);
	}

	private WindowCache publishMBeanIfNeeded() {
		if(publishMBean.getAndSet(false)) {
			Monitoring.registerMBean(mbean, "block_cache");
		}
		return this;
	}

	public WindowCacheStats getStats() {
		return statsRecorder.getStats();
	}

	private int hash(int packHash, long off) {
		return packHash + (int) (off >>> windowSizeShift);
	}

	private ByteWindow load(Pack pack, long offset) throws IOException {
		long startTime = System.nanoTime();
		if(pack.beginWindowCache())
			statsRecorder.recordOpenFiles(1);
		try {
			if(mmap)
				return pack.mmap(offset, windowSize);
			ByteArrayWindow w = pack.read(offset, windowSize);
			statsRecorder.recordLoadSuccess(System.nanoTime() - startTime);
			return w;
		} catch(IOException | RuntimeException | Error e) {
			close(pack);
			statsRecorder.recordLoadFailure(System.nanoTime() - startTime);
			throw e;
		} finally {
			statsRecorder.recordMisses(1);
		}
	}

	private PageRef<ByteWindow> createRef(Pack p, long o, ByteWindow v) {
		final PageRef<ByteWindow> ref = useStrongRefs
				? new StrongRef(p, o, v, queue)
				: new SoftRef(p, o, v, (SoftCleanupQueue) queue);
		statsRecorder.recordOpenBytes(ref.getPack(), ref.getSize());
		return ref;
	}

	private void clear(PageRef<ByteWindow> ref) {
		statsRecorder.recordOpenBytes(ref.getPack(), -ref.getSize());
		statsRecorder.recordEvictions(1);
		close(ref.getPack());
	}

	private void close(Pack pack) {
		if(pack.endWindowCache()) {
			statsRecorder.recordOpenFiles(-1);
		}
	}

	private boolean isFull() {
		return maxFiles < mbean.getOpenFileCount()
				|| maxBytes < mbean.getOpenByteCount();
	}

	private long toStart(long offset) {
		return (offset >>> windowSizeShift) << windowSizeShift;
	}

	private static int tableSize(WindowCacheConfig cfg) {
		final int wsz = cfg.getPackedGitWindowSize();
		final long limit = cfg.getPackedGitLimit();
		if(wsz <= 0)
			throw new IllegalArgumentException(JGitText.get().invalidWindowSize);
		if(limit < wsz)
			throw new IllegalArgumentException(JGitText.get().windowSizeMustBeLesserThanLimit);
		return (int) Math.min(5 * (limit / wsz) / 2, 2000000000);
	}

	private static int lockCount(WindowCacheConfig cfg) {
		return Math.max(cfg.getPackedGitOpenFiles(), 32);
	}

	private ByteWindow getOrLoad(Pack pack, long position)
			throws IOException {
		final int slot = slot(pack, position);
		final Entry e1 = table.get(slot);
		ByteWindow v = scan(e1, pack, position);
		if(v != null) {
			statsRecorder.recordHits(1);
			return v;
		}

		synchronized(lock(pack, position)) {
			Entry e2 = table.get(slot);
			if(e2 != e1) {
				v = scan(e2, pack, position);
				if(v != null) {
					statsRecorder.recordHits(1);
					return v;
				}
			}

			v = load(pack, position);
			final PageRef<ByteWindow> ref = createRef(pack, position, v);
			hit(ref);
			for(; ; ) {
				final Entry n = new Entry(clean(e2), ref);
				if(table.compareAndSet(slot, e2, n))
					break;
				e2 = table.get(slot);
			}
		}

		if(evictLock.tryLock()) {
			try {
				gc();
				evict();
			} finally {
				evictLock.unlock();
			}
		}

		return v;
	}

	private ByteWindow scan(Entry n, Pack pack, long position) {
		for(; n != null; n = n.next) {
			final PageRef<ByteWindow> r = n.ref;
			if(r.getPack() == pack && r.getPosition() == position) {
				final ByteWindow v = r.get();
				if(v != null) {
					hit(r);
					return v;
				}
				n.kill();
				break;
			}
		}
		return null;
	}

	private void hit(PageRef<?> r) {
		final long c = clock.get();
		clock.compareAndSet(c, c + 1);
		r.setLastAccess(c);
	}

	private void evict() {
		while(isFull()) {
			int ptr = rng.nextInt(tableSize);
			Entry old = null;
			int slot = 0;
			for(int b = evictBatch - 1; b >= 0; b--, ptr++) {
				if(tableSize <= ptr)
					ptr = 0;
				for(Entry e = table.get(ptr); e != null; e = e.next) {
					if(e.dead)
						continue;
					if(old == null || e.ref.getLastAccess() < old.ref
							.getLastAccess()) {
						old = e;
						slot = ptr;
					}
				}
			}
			if(old != null) {
				old.kill();
				gc();
				final Entry e1 = table.get(slot);
				table.compareAndSet(slot, e1, clean(e1));
			}
		}
	}

	private void removeAll() {
		for(int s = 0; s < tableSize; s++) {
			Entry e1;
			do {
				e1 = table.get(s);
				for(Entry e = e1; e != null; e = e.next)
					e.kill();
			} while(!table.compareAndSet(s, e1, null));
		}
		gc();
	}

	private void removeAll(Pack pack) {
		for(int s = 0; s < tableSize; s++) {
			final Entry e1 = table.get(s);
			boolean hasDead = false;
			for(Entry e = e1; e != null; e = e.next) {
				if(e.ref.getPack() == pack) {
					e.kill();
					hasDead = true;
				} else if(e.dead)
					hasDead = true;
			}
			if(hasDead)
				table.compareAndSet(s, e1, clean(e1));
		}
		gc();
	}

	private void gc() {
		queue.gc();
	}

	private int slot(Pack pack, long position) {
		return (hash(pack.hash, position) >>> 1) % tableSize;
	}

	private Lock lock(Pack pack, long position) {
		return locks[(hash(pack.hash, position) >>> 1) % locks.length];
	}

	private static Entry clean(Entry top) {
		while(top != null && top.dead) {
			top.ref.kill();
			top = top.next;
		}
		if(top == null)
			return null;
		final Entry n = clean(top.next);
		return n == top.next ? top : new Entry(n, top.ref);
	}

	private static class Entry {
		final Entry next;
		final PageRef<ByteWindow> ref;

		volatile boolean dead;

		Entry(Entry n, PageRef<ByteWindow> r) {
			next = n;
			ref = r;
		}

		final void kill() {
			dead = true;
			ref.kill();
		}
	}

	private interface PageRef<T> {

		T get();

		boolean kill();

		Pack getPack();

		long getPosition();

		int getSize();

		long getLastAccess();

		void setLastAccess(long time);

	}

	private static class SoftRef extends SoftReference<ByteWindow>
			implements PageRef<ByteWindow> {
		private final Pack pack;

		private final long position;

		private final int size;

		private long lastAccess;

		protected SoftRef(final Pack pack, final long position,
						  final ByteWindow v, final SoftCleanupQueue queue) {
			super(v, queue);
			this.pack = pack;
			this.position = position;
			this.size = v.size();
		}

		@Override
		public Pack getPack() {
			return pack;
		}

		@Override
		public long getPosition() {
			return position;
		}

		@Override
		public int getSize() {
			return size;
		}

		@Override
		public long getLastAccess() {
			return lastAccess;
		}

		@Override
		public void setLastAccess(long time) {
			this.lastAccess = time;
		}

		@Override
		public boolean kill() {
			return enqueue();
		}

	}

	private static class StrongRef implements PageRef<ByteWindow> {
		private ByteWindow referent;

		private final Pack pack;

		private final long position;

		private final int size;

		private long lastAccess;

		private final CleanupQueue queue;

		protected StrongRef(final Pack pack, final long position,
							final ByteWindow v, final CleanupQueue queue) {
			this.pack = pack;
			this.position = position;
			this.referent = v;
			this.size = v.size();
			this.queue = queue;
		}

		@Override
		public Pack getPack() {
			return pack;
		}

		@Override
		public long getPosition() {
			return position;
		}

		@Override
		public int getSize() {
			return size;
		}

		@Override
		public long getLastAccess() {
			return lastAccess;
		}

		@Override
		public void setLastAccess(long time) {
			this.lastAccess = time;
		}

		@Override
		public ByteWindow get() {
			return referent;
		}

		@Override
		public boolean kill() {
			if(referent == null) {
				return false;
			}
			referent = null;
			return queue.enqueue(this);
		}

	}

	private interface CleanupQueue {
		boolean enqueue(PageRef<ByteWindow> r);

		void gc();
	}

	private static class SoftCleanupQueue extends ReferenceQueue<ByteWindow>
			implements CleanupQueue {
		private final WindowCache wc;

		SoftCleanupQueue(WindowCache cache) {
			this.wc = cache;
		}

		@Override
		public boolean enqueue(PageRef<ByteWindow> r) {
			return false;
		}

		@Override
		public void gc() {
			SoftRef r;
			while((r = (SoftRef) poll()) != null) {
				wc.clear(r);

				final int s = wc.slot(r.getPack(), r.getPosition());
				final Entry e1 = wc.table.get(s);
				for(Entry n = e1; n != null; n = n.next) {
					if(n.ref == r) {
						n.dead = true;
						wc.table.compareAndSet(s, e1, clean(e1));
						break;
					}
				}
			}
		}
	}

	private static class StrongCleanupQueue implements CleanupQueue {
		private final WindowCache wc;

		private final ConcurrentLinkedQueue<PageRef<ByteWindow>> queue = new ConcurrentLinkedQueue<>();

		StrongCleanupQueue(WindowCache wc) {
			this.wc = wc;
		}

		@Override
		public boolean enqueue(PageRef<ByteWindow> r) {
			if(queue.contains(r)) {
				return false;
			}
			return queue.add(r);
		}

		@Override
		public void gc() {
			PageRef<ByteWindow> r;
			while((r = queue.poll()) != null) {
				wc.clear(r);

				final int s = wc.slot(r.getPack(), r.getPosition());
				final Entry e1 = wc.table.get(s);
				for(Entry n = e1; n != null; n = n.next) {
					if(n.ref == r) {
						n.dead = true;
						wc.table.compareAndSet(s, e1, clean(e1));
						break;
					}
				}
			}
		}
	}

	private static final class Lock {
	}
}
