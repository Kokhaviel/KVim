/*
 * Copyright (C) 2010, Christian Halstrick <christian.halstrick@sap.com>,
 * Copyright (C) 2010, Matthias Sohn <matthias.sohn@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.merge;

import org.eclipse.jgit.lib.Repository;

public class StrategyResolve extends ThreeWayMergeStrategy {

	@Override
	public ThreeWayMerger newMerger(Repository db) {
		return new ResolveMerger(db, false);
	}

	@Override
	public ThreeWayMerger newMerger(Repository db, boolean inCore) {
		return new ResolveMerger(db, inCore);
	}

	@Override
	public String getName() {
		return "resolve"; 
	}
}
