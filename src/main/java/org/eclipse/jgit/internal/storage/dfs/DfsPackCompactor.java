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

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jgit.internal.storage.reftable.ReftableConfig;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectIdSet;
import org.eclipse.jgit.revwalk.RevFlag;
import org.eclipse.jgit.revwalk.RevWalk;

public class DfsPackCompactor {
	private final List<DfsPackFile> srcPacks;
	private final List<DfsReftable> srcReftables;

	public DfsPackCompactor() {
		srcPacks = new ArrayList<>();
		srcReftables = new ArrayList<>();
	}

	public DfsPackCompactor add(DfsPackFile pack) {
		srcPacks.add(pack);
		return this;
	}

	public DfsPackCompactor add(DfsReftable table) {
		srcReftables.add(table);
		return this;
	}

	static ReftableConfig configureReftable(ReftableConfig cfg,
			DfsOutputStream out) {
		int bs = out.blockSize();
		if (bs > 0) {
			cfg = new ReftableConfig(cfg);
			cfg.setRefBlockSize(bs);
			cfg.setAlignBlocks(true);
		}
		return cfg;
	}
}
