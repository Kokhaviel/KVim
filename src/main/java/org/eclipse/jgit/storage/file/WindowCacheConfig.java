/*
 * Copyright (C) 2009, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.storage.file;

import org.eclipse.jgit.storage.pack.PackConfig;

public class WindowCacheConfig {
	public static final int KB = 1024;
	public static final int MB = 1024 * KB;
	private final int packedGitOpenFiles;
	private final long packedGitLimit;
	private boolean useStrongRefs;
	private final int packedGitWindowSize;
	private final boolean packedGitMMAP;
	private final int deltaBaseCacheLimit;
	private final int streamFileThreshold;
	private final boolean exposeStats;

	public WindowCacheConfig() {
		packedGitOpenFiles = 128;
		packedGitLimit = 10 * MB;
		useStrongRefs = false;
		packedGitWindowSize = 8 * KB;
		packedGitMMAP = false;
		deltaBaseCacheLimit = 10 * MB;
		streamFileThreshold = PackConfig.DEFAULT_BIG_FILE_THRESHOLD;
		exposeStats = true;
	}

	public int getPackedGitOpenFiles() {
		return packedGitOpenFiles;
	}

	public long getPackedGitLimit() {
		return packedGitLimit;
	}

	public boolean isPackedGitUseStrongRefs() {
		return useStrongRefs;
	}

	public int getPackedGitWindowSize() {
		return packedGitWindowSize;
	}

	public boolean isPackedGitMMAP() {
		return packedGitMMAP;
	}

	public int getDeltaBaseCacheLimit() {
		return deltaBaseCacheLimit;
	}

	public int getStreamFileThreshold() {
		return streamFileThreshold;
	}

	public boolean getExposeStatsViaJmx() {
		return exposeStats;
	}

}
