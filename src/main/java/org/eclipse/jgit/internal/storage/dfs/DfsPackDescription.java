/*
 * Copyright (C) 2011, 2013 Google Inc., and others. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.dfs;

import static org.eclipse.jgit.internal.storage.pack.PackExt.PACK;
import static org.eclipse.jgit.internal.storage.pack.PackExt.REFTABLE;

import java.util.Arrays;
import java.util.Comparator;

import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.internal.storage.dfs.DfsObjDatabase.PackSource;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.eclipse.jgit.internal.storage.reftable.ReftableWriter;

public class DfsPackDescription {

	public static Comparator<DfsPackDescription> objectLookupComparator() {
		return objectLookupComparator(PackSource.DEFAULT_COMPARATOR);
	}

	public static Comparator<DfsPackDescription> objectLookupComparator(
			Comparator<PackSource> packSourceComparator) {
		return Comparator.comparing(
						DfsPackDescription::getPackSource, packSourceComparator)
				.thenComparing((a, b) -> {
					PackSource as = a.getPackSource();
					PackSource bs = b.getPackSource();

					if(as == bs && isGC(as)) {
						int cmp = Long.signum(a.getFileSize(PACK) - b.getFileSize(PACK));
						if(cmp != 0) {
							return cmp;
						}
					}

					int cmp = Long.signum(b.getLastModified() - a.getLastModified());
					if(cmp != 0) {
						return cmp;
					}

					return Long.signum(a.getObjectCount() - b.getObjectCount());
				});
	}

	static Comparator<DfsPackDescription> reftableComparator() {
		return (a, b) -> {
			int c = PackSource.DEFAULT_COMPARATOR.reversed()
					.compare(a.getPackSource(), b.getPackSource());
			if(c != 0) {
				return c;
			}

			c = Long.signum(a.getMaxUpdateIndex() - b.getMaxUpdateIndex());
			if(c != 0) {
				return c;
			}

			return Long.signum(a.getLastModified() - b.getLastModified());
		};
	}

	static Comparator<DfsPackDescription> reuseComparator() {
		return (a, b) -> {
			PackSource as = a.getPackSource();
			PackSource bs = b.getPackSource();

			if(as == bs && DfsPackDescription.isGC(as)) {
				return Long.signum(b.getFileSize(PACK) - a.getFileSize(PACK));
			}

			return 0;
		};
	}

	private final DfsRepositoryDescription repoDesc;
	private final String packName;
	private final PackSource packSource;
	private long lastModified;
	private long[] sizeMap;
	private int[] blockSizeMap;
	private long objectCount;
	private long deltaCount;
	private long maxUpdateIndex;

	private int extensions;

	public DfsPackDescription(DfsRepositoryDescription repoDesc, String name,
							  @NonNull PackSource packSource) {
		this.repoDesc = repoDesc;
		int dot = name.lastIndexOf('.');
		this.packName = (dot < 0) ? name : name.substring(0, dot);
		this.packSource = packSource;

		int extCnt = PackExt.values().length;
		sizeMap = new long[extCnt];
		blockSizeMap = new int[extCnt];
	}

	public DfsRepositoryDescription getRepositoryDescription() {
		return repoDesc;
	}

	public void addFileExt(PackExt ext) {
		extensions |= ext.getBit();
	}

	public boolean hasFileExt(PackExt ext) {
		return (extensions & ext.getBit()) != 0;
	}

	public String getFileName(PackExt ext) {
		return packName + '.' + ext.getExtension();
	}

	public DfsStreamKey getStreamKey(PackExt ext) {
		return DfsStreamKey.of(getRepositoryDescription(), getFileName(ext),
				ext);
	}

	@NonNull
	public PackSource getPackSource() {
		return packSource;
	}

	public long getLastModified() {
		return lastModified;
	}

	public DfsPackDescription setLastModified(long timeMillis) {
		lastModified = timeMillis;
		return this;
	}

	public DfsPackDescription setMinUpdateIndex(long min) {
		return this;
	}

	public long getMaxUpdateIndex() {
		return maxUpdateIndex;
	}

	public DfsPackDescription setMaxUpdateIndex(long max) {
		maxUpdateIndex = max;
		return this;
	}

	public DfsPackDescription setFileSize(PackExt ext, long bytes) {
		int i = ext.getPosition();
		if(i >= sizeMap.length) {
			sizeMap = Arrays.copyOf(sizeMap, i + 1);
		}
		sizeMap[i] = Math.max(0, bytes);
		return this;
	}

	public long getFileSize(PackExt ext) {
		int i = ext.getPosition();
		return i < sizeMap.length ? sizeMap[i] : 0;
	}

	public int getBlockSize(PackExt ext) {
		int i = ext.getPosition();
		return i < blockSizeMap.length ? blockSizeMap[i] : 0;
	}

	public DfsPackDescription setBlockSize(PackExt ext, int blockSize) {
		int i = ext.getPosition();
		if(i >= blockSizeMap.length) {
			blockSizeMap = Arrays.copyOf(blockSizeMap, i + 1);
		}
		blockSizeMap[i] = Math.max(0, blockSize);
		return this;
	}

	public long getObjectCount() {
		return objectCount;
	}

	public DfsPackDescription setObjectCount(long cnt) {
		objectCount = Math.max(0, cnt);
		return this;
	}

	public long getDeltaCount() {
		return deltaCount;
	}

	void setReftableStats(ReftableWriter.Stats stats) {
		setMinUpdateIndex(stats.minUpdateIndex());
		setMaxUpdateIndex(stats.maxUpdateIndex());
		setFileSize(REFTABLE, stats.totalBytes());
		setBlockSize(REFTABLE, stats.refBlockSize());
	}

	public DfsPackDescription setIndexVersion() {
		return this;
	}

	@Override
	public int hashCode() {
		return packName.hashCode();
	}

	@Override
	public boolean equals(Object b) {
		if(b instanceof DfsPackDescription) {
			DfsPackDescription desc = (DfsPackDescription) b;
			return packName.equals(desc.packName) &&
					getRepositoryDescription().equals(desc.getRepositoryDescription());
		}
		return false;
	}

	static boolean isGC(PackSource s) {
		switch(s) {
			case GC:
			case GC_REST:
				return true;
			default:
				return false;
		}
	}

	@Override
	public String toString() {
		return getFileName(PackExt.PACK);
	}
}
