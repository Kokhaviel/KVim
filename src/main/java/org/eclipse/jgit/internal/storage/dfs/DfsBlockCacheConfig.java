/*
 * Copyright (C) 2011, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.dfs;

import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_CORE_SECTION;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_DFS_SECTION;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_BLOCK_LIMIT;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_BLOCK_SIZE;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_CONCURRENCY_LEVEL;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_STREAM_RATIO;

import java.text.MessageFormat;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.function.Consumer;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.eclipse.jgit.lib.Config;

public class DfsBlockCacheConfig {
	public static final int KB = 1024;
	public static final int MB = 1024 * KB;
	public static final int DEFAULT_CACHE_HOT_MAX = 1;

	private long blockLimit;
	private int blockSize;
	private double streamRatio;
	private int concurrencyLevel;

	private Consumer<Long> refLock;
	private Map<PackExt, Integer> cacheHotMap;

	private IndexEventConsumer indexEventConsumer;

	public DfsBlockCacheConfig() {
		setBlockLimit(32 * MB);
		setBlockSize(64 * KB);
		setStreamRatio(0.30);
		setConcurrencyLevel(32);
		cacheHotMap = Collections.emptyMap();
	}

	public long getBlockLimit() {
		return blockLimit;
	}

	public DfsBlockCacheConfig setBlockLimit(long newLimit) {
		if (newLimit <= 0) {
			throw new IllegalArgumentException(MessageFormat.format(JGitText.get().blockLimitNotPositive, newLimit));
		}
		blockLimit = newLimit;
		return this;
	}

	public int getBlockSize() {
		return blockSize;
	}

	public DfsBlockCacheConfig setBlockSize(int newSize) {
		int size = Math.max(512, newSize);
		if ((size & (size - 1)) != 0) {
			throw new IllegalArgumentException(
					JGitText.get().blockSizeNotPowerOf2);
		}
		blockSize = size;
		return this;
	}

	public int getConcurrencyLevel() {
		return concurrencyLevel;
	}

	public DfsBlockCacheConfig setConcurrencyLevel(final int newConcurrencyLevel) {
		concurrencyLevel = newConcurrencyLevel;
		return this;
	}

	public double getStreamRatio() {
		return streamRatio;
	}

	public DfsBlockCacheConfig setStreamRatio(double ratio) {
		streamRatio = Math.max(0, Math.min(ratio, 1.0));
		return this;
	}

	public Consumer<Long> getRefLockWaitTimeConsumer() {
		return refLock;
	}

	public Map<PackExt, Integer> getCacheHotMap() {
		return cacheHotMap;
	}

	public IndexEventConsumer getIndexEventConsumer() {
		return indexEventConsumer;
	}

	public interface IndexEventConsumer {

		void acceptRequestedEvent(int packExtPos, boolean cacheHit,
				long loadMicros, long bytes, Duration lastEvictionDuration);

		default void acceptEvictedEvent(int totalCacheHitCount) {
		}

		default boolean shouldReportEvictedEvent() {
			return false;
		}
	}
}