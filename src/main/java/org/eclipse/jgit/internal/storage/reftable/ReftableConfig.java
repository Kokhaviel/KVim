/*
 * Copyright (C) 2017, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.reftable;

import static org.eclipse.jgit.internal.storage.reftable.ReftableConstants.MAX_BLOCK_SIZE;

import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Repository;

public class ReftableConfig {
	private int refBlockSize = 4 << 10;
	private int logBlockSize;
	private int restartInterval;
	private int maxIndexLevels;
	private boolean alignBlocks = true;
	private boolean indexObjects = true;

	public ReftableConfig() {
	}

	public ReftableConfig(Repository db) {
		fromConfig(db.getConfig());
	}

	public ReftableConfig(Config cfg) {
		fromConfig(cfg);
	}

	public ReftableConfig(ReftableConfig cfg) {
		this.refBlockSize = cfg.refBlockSize;
		this.logBlockSize = cfg.logBlockSize;
		this.restartInterval = cfg.restartInterval;
		this.maxIndexLevels = cfg.maxIndexLevels;
		this.alignBlocks = cfg.alignBlocks;
		this.indexObjects = cfg.indexObjects;
	}

	public int getRefBlockSize() {
		return refBlockSize;
	}

	public void setRefBlockSize(int szBytes) {
		if(szBytes > MAX_BLOCK_SIZE) {
			throw new IllegalArgumentException();
		}
		refBlockSize = Math.max(0, szBytes);
	}

	public int getLogBlockSize() {
		return logBlockSize;
	}

	public int getRestartInterval() {
		return restartInterval;
	}

	public int getMaxIndexLevels() {
		return maxIndexLevels;
	}

	public boolean isAlignBlocks() {
		return alignBlocks;
	}

	public void setAlignBlocks(boolean align) {
		alignBlocks = align;
	}

	public boolean isIndexObjects() {
		return indexObjects;
	}

	public void setIndexObjects(boolean index) {
		indexObjects = index;
	}

	public void fromConfig(Config rc) {
		refBlockSize = rc.getInt("reftable", "blockSize", refBlockSize);
		logBlockSize = rc.getInt("reftable", "logBlockSize", logBlockSize);
		restartInterval = rc.getInt("reftable", "restartInterval", restartInterval);
		maxIndexLevels = rc.getInt("reftable", "indexLevels", maxIndexLevels);
		alignBlocks = rc.getBoolean("reftable", "alignBlocks", alignBlocks);
		indexObjects = rc.getBoolean("reftable", "indexObjects", indexObjects);
	}
}
