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

import org.eclipse.jgit.storage.pack.PackConfig;

public class DfsReaderOptions {
	public static final int KiB = 1024;
	public static final int MiB = 1024 * KiB;

	private int deltaBaseCacheLimit;
	private int streamFileThreshold;

	private int streamPackBufferSize;
	private boolean loadRevIndexInParallel;

	public DfsReaderOptions() {
		setDeltaBaseCacheLimit(10 * MiB);
		setStreamFileThreshold(PackConfig.DEFAULT_BIG_FILE_THRESHOLD);
	}

	public int getDeltaBaseCacheLimit() {
		return deltaBaseCacheLimit;
	}

	public DfsReaderOptions setDeltaBaseCacheLimit(int maxBytes) {
		deltaBaseCacheLimit = Math.max(0, maxBytes);
		return this;
	}

	public int getStreamFileThreshold() {
		return streamFileThreshold;
	}

	public DfsReaderOptions setStreamFileThreshold(int newLimit) {
		streamFileThreshold = Math.max(0, newLimit);
		return this;
	}

	public int getStreamPackBufferSize() {
		return streamPackBufferSize;
	}

	public boolean shouldLoadRevIndexInParallel() {
		return loadRevIndexInParallel;
	}

}
