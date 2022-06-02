/*
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.revwalk;

import java.io.IOException;

abstract class Generator {
	static final int SORT_COMMIT_TIME_DESC = 1;
	static final int HAS_REWRITE = 1 << 1;
	static final int NEEDS_REWRITE = 1 << 2;
	static final int SORT_TOPO = 1 << 3;
	static final int HAS_UNINTERESTING = 1 << 4;

	protected final boolean firstParent;

	protected Generator(boolean firstParent) {
		this.firstParent = firstParent;
	}

	void shareFreeList(BlockRevQueue q) {
	}

	abstract int outputType();

	abstract RevCommit next() throws IOException;
}
