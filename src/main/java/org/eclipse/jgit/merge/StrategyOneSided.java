/*
 * Copyright (C) 2008-2009, Google Inc.
 * Copyright (C) 2009, Robin Rosenberg <robin.rosenberg@dewire.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.merge;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

public class StrategyOneSided extends MergeStrategy {
	private final String strategyName;

	private final int treeIndex;

	protected StrategyOneSided(String name, int index) {
		strategyName = name;
		treeIndex = index;
	}

	@Override
	public String getName() {
		return strategyName;
	}

	@Override
	public Merger newMerger(Repository db) {
		return new OneSide(db, treeIndex);
	}

	@Override
	public Merger newMerger(Repository db, boolean inCore) {
		return new OneSide(db, treeIndex);
	}

	static class OneSide extends Merger {
		private final int treeIndex;

		protected OneSide(Repository local, int index) {
			super(local);
			treeIndex = index;
		}

		@Override
		protected boolean mergeImpl() {
			return treeIndex < sourceTrees.length;
		}

		@Override
		public ObjectId getResultTreeId() {
			return sourceTrees[treeIndex];
		}

		@Override
		public ObjectId getBaseCommitId() {
			return null;
		}
	}
}
