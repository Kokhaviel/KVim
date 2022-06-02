/*
 * Copyright (C) 2008-2010, Google Inc.
 * Copyright (C) 2008, Marek Zawirski <marek.zawirski@gmail.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.storage.pack;

import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Repository;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;

import static org.eclipse.jgit.lib.ConfigConstants.*;

public class PackConfig {

	public static final boolean DEFAULT_REUSE_DELTAS = true;
	public static final boolean DEFAULT_REUSE_OBJECTS = true;
	public static final boolean DEFAULT_PRESERVE_OLD_PACKS = false;
	public static final boolean DEFAULT_PRUNE_PRESERVED = false;
	public static final boolean DEFAULT_DELTA_COMPRESS = true;
	public static final boolean DEFAULT_DELTA_BASE_AS_OFFSET = false;
	public static final int DEFAULT_MAX_DELTA_DEPTH = 50;
	public static final int DEFAULT_DELTA_SEARCH_WINDOW_SIZE = 10;
	private static final int MB = 1 << 20;
	public static final int DEFAULT_BIG_FILE_THRESHOLD = 50 * MB;
	public static final boolean DEFAULT_WAIT_PREVENT_RACY_PACK = false;
	public static final long DEFAULT_MINSIZE_PREVENT_RACY_PACK = 100 * MB;
	public static final long DEFAULT_DELTA_CACHE_SIZE = 50 * 1024 * 1024;
	public static final int DEFAULT_DELTA_CACHE_LIMIT = 100;
	public static final int DEFAULT_INDEX_VERSION = 2;
	public static final boolean DEFAULT_BUILD_BITMAPS = true;
	public static final int DEFAULT_BITMAP_CONTIGUOUS_COMMIT_COUNT = 100;
	public static final int DEFAULT_BITMAP_RECENT_COMMIT_COUNT = 20000;
	public static final int DEFAULT_BITMAP_RECENT_COMMIT_SPAN = 100;
	public static final int DEFAULT_BITMAP_DISTANT_COMMIT_SPAN = 5000;
	public static final int DEFAULT_BITMAP_EXCESSIVE_BRANCH_COUNT = 100;
	public static final int DEFAULT_BITMAP_INACTIVE_BRANCH_AGE_IN_DAYS = 90;
	public static final Duration DEFAULT_SEARCH_FOR_REUSE_TIMEOUT = Duration
			.ofSeconds(Integer.MAX_VALUE);

	private int compressionLevel = Deflater.DEFAULT_COMPRESSION;
	private boolean reuseDeltas = DEFAULT_REUSE_DELTAS;
	private boolean reuseObjects = DEFAULT_REUSE_OBJECTS;
	private boolean preserveOldPacks = DEFAULT_PRESERVE_OLD_PACKS;
	private boolean prunePreserved = DEFAULT_PRUNE_PRESERVED;
	private boolean deltaBaseAsOffset = DEFAULT_DELTA_BASE_AS_OFFSET;
	private boolean deltaCompress = DEFAULT_DELTA_COMPRESS;
	private int maxDeltaDepth = DEFAULT_MAX_DELTA_DEPTH;
	private int deltaSearchWindowSize = DEFAULT_DELTA_SEARCH_WINDOW_SIZE;
	private long deltaSearchMemoryLimit;
	private long deltaCacheSize = DEFAULT_DELTA_CACHE_SIZE;
	private int deltaCacheLimit = DEFAULT_DELTA_CACHE_LIMIT;
	private int bigFileThreshold = DEFAULT_BIG_FILE_THRESHOLD;
	private boolean waitPreventRacyPack = DEFAULT_WAIT_PREVENT_RACY_PACK;
	private long minSizePreventRacyPack = DEFAULT_MINSIZE_PREVENT_RACY_PACK;
	private int threads;
	private Executor executor;
	private int indexVersion = DEFAULT_INDEX_VERSION;
	private boolean buildBitmaps = DEFAULT_BUILD_BITMAPS;
	private int bitmapContiguousCommitCount = DEFAULT_BITMAP_CONTIGUOUS_COMMIT_COUNT;
	private int bitmapRecentCommitCount = DEFAULT_BITMAP_RECENT_COMMIT_COUNT;
	private int bitmapRecentCommitSpan = DEFAULT_BITMAP_RECENT_COMMIT_SPAN;
	private int bitmapDistantCommitSpan = DEFAULT_BITMAP_DISTANT_COMMIT_SPAN;
	private int bitmapExcessiveBranchCount = DEFAULT_BITMAP_EXCESSIVE_BRANCH_COUNT;
	private int bitmapInactiveBranchAgeInDays = DEFAULT_BITMAP_INACTIVE_BRANCH_AGE_IN_DAYS;
	private Duration searchForReuseTimeout = DEFAULT_SEARCH_FOR_REUSE_TIMEOUT;
	private boolean cutDeltaChains;
	private boolean singlePack;

	public PackConfig() {
	}

	public PackConfig(Repository db) {
		fromConfig(db.getConfig());
	}

	public PackConfig(Config cfg) {
		fromConfig(cfg);
	}

	public PackConfig(PackConfig cfg) {
		this.compressionLevel = cfg.compressionLevel;
		this.reuseDeltas = cfg.reuseDeltas;
		this.reuseObjects = cfg.reuseObjects;
		this.preserveOldPacks = cfg.preserveOldPacks;
		this.prunePreserved = cfg.prunePreserved;
		this.deltaBaseAsOffset = cfg.deltaBaseAsOffset;
		this.deltaCompress = cfg.deltaCompress;
		this.maxDeltaDepth = cfg.maxDeltaDepth;
		this.deltaSearchWindowSize = cfg.deltaSearchWindowSize;
		this.deltaSearchMemoryLimit = cfg.deltaSearchMemoryLimit;
		this.deltaCacheSize = cfg.deltaCacheSize;
		this.deltaCacheLimit = cfg.deltaCacheLimit;
		this.bigFileThreshold = cfg.bigFileThreshold;
		this.waitPreventRacyPack = cfg.waitPreventRacyPack;
		this.minSizePreventRacyPack = cfg.minSizePreventRacyPack;
		this.threads = cfg.threads;
		this.executor = cfg.executor;
		this.indexVersion = cfg.indexVersion;
		this.buildBitmaps = cfg.buildBitmaps;
		this.bitmapContiguousCommitCount = cfg.bitmapContiguousCommitCount;
		this.bitmapRecentCommitCount = cfg.bitmapRecentCommitCount;
		this.bitmapRecentCommitSpan = cfg.bitmapRecentCommitSpan;
		this.bitmapDistantCommitSpan = cfg.bitmapDistantCommitSpan;
		this.bitmapExcessiveBranchCount = cfg.bitmapExcessiveBranchCount;
		this.bitmapInactiveBranchAgeInDays = cfg.bitmapInactiveBranchAgeInDays;
		this.cutDeltaChains = cfg.cutDeltaChains;
		this.singlePack = cfg.singlePack;
		this.searchForReuseTimeout = cfg.searchForReuseTimeout;
	}

	public boolean isReuseDeltas() {
		return reuseDeltas;
	}

	public void setReuseDeltas(boolean reuseDeltas) {
		this.reuseDeltas = reuseDeltas;
	}

	public boolean isReuseObjects() {
		return reuseObjects;
	}

	public void setReuseObjects(boolean reuseObjects) {
		this.reuseObjects = reuseObjects;
	}

	public boolean isPreserveOldPacks() {
		return preserveOldPacks;
	}

	public boolean isPrunePreserved() {
		return prunePreserved;
	}

	public boolean isDeltaBaseAsOffset() {
		return deltaBaseAsOffset;
	}

	public boolean isDeltaCompress() {
		return deltaCompress;
	}

	public void setDeltaCompress(boolean deltaCompress) {
		this.deltaCompress = deltaCompress;
	}

	public int getMaxDeltaDepth() {
		return maxDeltaDepth;
	}

	public void setMaxDeltaDepth(int maxDeltaDepth) {
		this.maxDeltaDepth = maxDeltaDepth;
	}

	public boolean getCutDeltaChains() {
		return cutDeltaChains;
	}

	public void setCutDeltaChains(boolean cut) {
		cutDeltaChains = cut;
	}

	public boolean getSinglePack() {
		return singlePack;
	}

	public void setSinglePack(boolean single) {
		singlePack = single;
	}

	public int getDeltaSearchWindowSize() {
		return deltaSearchWindowSize;
	}

	public void setDeltaSearchWindowSize(int objectCount) {
		if(objectCount <= 2)
			setDeltaCompress(false);
		else
			deltaSearchWindowSize = objectCount;
	}

	public long getDeltaSearchMemoryLimit() {
		return deltaSearchMemoryLimit;
	}

	public void setDeltaSearchMemoryLimit(long memoryLimit) {
		deltaSearchMemoryLimit = memoryLimit;
	}

	public long getDeltaCacheSize() {
		return deltaCacheSize;
	}

	public void setDeltaCacheSize(long size) {
		deltaCacheSize = size;
	}

	public int getDeltaCacheLimit() {
		return deltaCacheLimit;
	}

	public void setDeltaCacheLimit(int size) {
		deltaCacheLimit = size;
	}

	public int getBigFileThreshold() {
		return bigFileThreshold;
	}

	public void setBigFileThreshold(int bigFileThreshold) {
		this.bigFileThreshold = bigFileThreshold;
	}

	public boolean isWaitPreventRacyPack() {
		return waitPreventRacyPack;
	}

	public boolean doWaitPreventRacyPack(long packSize) {
		return isWaitPreventRacyPack()
				&& packSize > getMinSizePreventRacyPack();
	}

	public void setWaitPreventRacyPack(boolean waitPreventRacyPack) {
		this.waitPreventRacyPack = waitPreventRacyPack;
	}

	public long getMinSizePreventRacyPack() {
		return minSizePreventRacyPack;
	}

	public void setMinSizePreventRacyPack(long minSizePreventRacyPack) {
		this.minSizePreventRacyPack = minSizePreventRacyPack;
	}

	public int getCompressionLevel() {
		return compressionLevel;
	}

	public void setCompressionLevel(int level) {
		compressionLevel = level;
	}

	public int getThreads() {
		return threads;
	}

	public void setThreads(int threads) {
		this.threads = threads;
	}

	public Executor getExecutor() {
		return executor;
	}

	public int getIndexVersion() {
		return indexVersion;
	}

	public void setIndexVersion(int version) {
		indexVersion = version;
	}

	public boolean isBuildBitmaps() {
		return buildBitmaps;
	}

	public void setBuildBitmaps(boolean buildBitmaps) {
		this.buildBitmaps = buildBitmaps;
	}

	public int getBitmapContiguousCommitCount() {
		return bitmapContiguousCommitCount;
	}

	public void setBitmapContiguousCommitCount(int count) {
		bitmapContiguousCommitCount = count;
	}

	public int getBitmapRecentCommitCount() {
		return bitmapRecentCommitCount;
	}

	public void setBitmapRecentCommitCount(int count) {
		bitmapRecentCommitCount = count;
	}

	public int getBitmapRecentCommitSpan() {
		return bitmapRecentCommitSpan;
	}

	public void setBitmapRecentCommitSpan(int span) {
		bitmapRecentCommitSpan = span;
	}

	public int getBitmapDistantCommitSpan() {
		return bitmapDistantCommitSpan;
	}

	public void setBitmapDistantCommitSpan(int span) {
		bitmapDistantCommitSpan = span;
	}

	public int getBitmapExcessiveBranchCount() {
		return bitmapExcessiveBranchCount;
	}

	public void setBitmapExcessiveBranchCount(int count) {
		bitmapExcessiveBranchCount = count;
	}

	public int getBitmapInactiveBranchAgeInDays() {
		return bitmapInactiveBranchAgeInDays;
	}

	public Duration getSearchForReuseTimeout() {
		return searchForReuseTimeout;
	}

	public void setBitmapInactiveBranchAgeInDays(int ageInDays) {
		bitmapInactiveBranchAgeInDays = ageInDays;
	}

	public void setSearchForReuseTimeout(Duration timeout) {
		searchForReuseTimeout = timeout;
	}

	public void fromConfig(Config rc) {
		setMaxDeltaDepth(rc.getInt(CONFIG_PACK_SECTION, CONFIG_KEY_DEPTH,
				getMaxDeltaDepth()));
		setDeltaSearchWindowSize(rc.getInt(CONFIG_PACK_SECTION,
				CONFIG_KEY_WINDOW, getDeltaSearchWindowSize()));
		setDeltaSearchMemoryLimit(rc.getLong(CONFIG_PACK_SECTION,
				CONFIG_KEY_WINDOW_MEMORY, getDeltaSearchMemoryLimit()));
		setDeltaCacheSize(rc.getLong(CONFIG_PACK_SECTION,
				CONFIG_KEY_DELTA_CACHE_SIZE, getDeltaCacheSize()));
		setDeltaCacheLimit(rc.getInt(CONFIG_PACK_SECTION,
				CONFIG_KEY_DELTA_CACHE_LIMIT, getDeltaCacheLimit()));
		setCompressionLevel(rc.getInt(CONFIG_PACK_SECTION,
				CONFIG_KEY_COMPRESSION, rc.getInt(CONFIG_CORE_SECTION,
						CONFIG_KEY_COMPRESSION, getCompressionLevel())));
		setIndexVersion(rc.getInt(CONFIG_PACK_SECTION,
				CONFIG_KEY_INDEXVERSION,
				getIndexVersion()));
		setBigFileThreshold(rc.getInt(CONFIG_CORE_SECTION,
				CONFIG_KEY_BIGFILE_THRESHOLD, getBigFileThreshold()));
		setThreads(rc.getInt(CONFIG_PACK_SECTION, CONFIG_KEY_THREADS,
				getThreads()));
		setReuseDeltas(rc.getBoolean(CONFIG_PACK_SECTION,
				CONFIG_KEY_REUSE_DELTAS, isReuseDeltas()));
		setReuseObjects(rc.getBoolean(CONFIG_PACK_SECTION,
				CONFIG_KEY_REUSE_OBJECTS, isReuseObjects()));
		setDeltaCompress(rc.getBoolean(CONFIG_PACK_SECTION,
				CONFIG_KEY_DELTA_COMPRESSION, isDeltaCompress()));
		setCutDeltaChains(rc.getBoolean(CONFIG_PACK_SECTION,
				CONFIG_KEY_CUT_DELTACHAINS, getCutDeltaChains()));
		setSinglePack(rc.getBoolean(CONFIG_PACK_SECTION,
				CONFIG_KEY_SINGLE_PACK,
				getSinglePack()));
		setBuildBitmaps(rc.getBoolean(CONFIG_PACK_SECTION,
				CONFIG_KEY_BUILD_BITMAPS, isBuildBitmaps()));
		setBitmapContiguousCommitCount(rc.getInt(CONFIG_PACK_SECTION,
				CONFIG_KEY_BITMAP_CONTIGUOUS_COMMIT_COUNT,
				getBitmapContiguousCommitCount()));
		setBitmapRecentCommitCount(rc.getInt(CONFIG_PACK_SECTION,
				CONFIG_KEY_BITMAP_RECENT_COMMIT_COUNT,
				getBitmapRecentCommitCount()));
		setBitmapRecentCommitSpan(rc.getInt(CONFIG_PACK_SECTION,
				CONFIG_KEY_BITMAP_RECENT_COMMIT_COUNT,
				getBitmapRecentCommitSpan()));
		setBitmapDistantCommitSpan(rc.getInt(CONFIG_PACK_SECTION,
				CONFIG_KEY_BITMAP_DISTANT_COMMIT_SPAN,
				getBitmapDistantCommitSpan()));
		setBitmapExcessiveBranchCount(rc.getInt(CONFIG_PACK_SECTION,
				CONFIG_KEY_BITMAP_EXCESSIVE_BRANCH_COUNT,
				getBitmapExcessiveBranchCount()));
		setBitmapInactiveBranchAgeInDays(rc.getInt(CONFIG_PACK_SECTION,
				CONFIG_KEY_BITMAP_INACTIVE_BRANCH_AGE_INDAYS,
				getBitmapInactiveBranchAgeInDays()));
		setSearchForReuseTimeout(Duration.ofSeconds(rc.getTimeUnit(
				CONFIG_PACK_SECTION, null,
				CONFIG_KEY_SEARCH_FOR_REUSE_TIMEOUT,
				getSearchForReuseTimeout().getSeconds(), TimeUnit.SECONDS)));
		setWaitPreventRacyPack(rc.getBoolean(CONFIG_PACK_SECTION,
				CONFIG_KEY_WAIT_PREVENT_RACYPACK, isWaitPreventRacyPack()));
		setMinSizePreventRacyPack(rc.getLong(CONFIG_PACK_SECTION,
				CONFIG_KEY_MIN_SIZE_PREVENT_RACYPACK,
				getMinSizePreventRacyPack()));
	}

	@Override
	public String toString() {
		return "maxDeltaDepth=" + getMaxDeltaDepth() +
				", deltaSearchWindowSize=" + getDeltaSearchWindowSize() +
				", deltaSearchMemoryLimit=" +
				getDeltaSearchMemoryLimit() +
				", deltaCacheSize=" + getDeltaCacheSize() +
				", deltaCacheLimit=" + getDeltaCacheLimit() +
				", compressionLevel=" + getCompressionLevel() +
				", indexVersion=" + getIndexVersion() +
				", bigFileThreshold=" + getBigFileThreshold() +
				", threads=" + getThreads() +
				", reuseDeltas=" + isReuseDeltas() +
				", reuseObjects=" + isReuseObjects() +
				", deltaCompress=" + isDeltaCompress() +
				", buildBitmaps=" + isBuildBitmaps() +
				", bitmapContiguousCommitCount=" +
				getBitmapContiguousCommitCount() +
				", bitmapRecentCommitCount=" +
				getBitmapRecentCommitCount() +
				", bitmapRecentCommitSpan=" +
				getBitmapRecentCommitSpan() +
				", bitmapDistantCommitSpan=" +
				getBitmapDistantCommitSpan() +
				", bitmapExcessiveBranchCount=" +
				getBitmapExcessiveBranchCount() +
				", bitmapInactiveBranchAge=" +
				getBitmapInactiveBranchAgeInDays() +
				", searchForReuseTimeout" +
				getSearchForReuseTimeout() +
				", singlePack=" + getSinglePack();
	}
}
