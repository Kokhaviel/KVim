/*
 * Copyright (C) 2012, Research In Motion Limited and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.merge;

import org.eclipse.jgit.lib.Repository;

public class StrategyRecursive extends StrategyResolve {

	@Override
	public ThreeWayMerger newMerger(Repository db) {
		return new RecursiveMerger(db, false);
	}

	@Override
	public ThreeWayMerger newMerger(Repository db, boolean inCore) {
		return new RecursiveMerger(db, inCore);
	}

	@Override
	public String getName() {
		return "recursive";
	}
}
