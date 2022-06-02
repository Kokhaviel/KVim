/*
 * Copyright (C) 2010, Jens Baumgart <jens.baumgart@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.treewalk.filter;

import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.WorkingTreeIterator;

import java.io.IOException;

public class NotIgnoredFilter extends TreeFilter {
	private final int index;

	public NotIgnoredFilter(int workdirTreeIndex) {
		this.index = workdirTreeIndex;
	}

	@Override
	public boolean include(TreeWalk tw) throws IOException {
		WorkingTreeIterator i = tw.getTree(index);
		return i == null || !i.isEntryIgnored();
	}

	@Override
	public boolean shouldBeRecursive() {
		return false;
	}

	@Override
	public TreeFilter clone() {
		return this;
	}

	@SuppressWarnings("nls")
	@Override
	public String toString() {
		return "NotIgnored(" + index + ")";
	}
}
