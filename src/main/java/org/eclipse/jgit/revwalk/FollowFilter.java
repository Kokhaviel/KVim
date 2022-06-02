/*
 * Copyright (C) 2010, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.revwalk;

import org.eclipse.jgit.diff.DiffConfig;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

import java.io.IOException;

public class FollowFilter extends TreeFilter {

	public static FollowFilter create(String path, DiffConfig cfg) {
		return new FollowFilter(PathFilter.create(path), cfg);
	}

	private final PathFilter path;
	final DiffConfig cfg;

	private RenameCallback renameCallback;

	FollowFilter(PathFilter path, DiffConfig cfg) {
		this.path = path;
		this.cfg = cfg;
	}

	public String getPath() {
		return path.getPath();
	}

	@Override
	public boolean include(TreeWalk walker) throws IOException {
		return path.include(walker) && ANY_DIFF.include(walker);
	}

	@Override
	public boolean shouldBeRecursive() {
		return path.shouldBeRecursive() || ANY_DIFF.shouldBeRecursive();
	}

	@Override
	public TreeFilter clone() {
		return new FollowFilter(path.clone(), cfg);
	}

	@Override
	public String toString() {
		return "(FOLLOW(" + path.toString() + ")"
				+ " AND "
				+ ANY_DIFF + ")";
	}

	public RenameCallback getRenameCallback() {
		return renameCallback;
	}

	public void setRenameCallback(RenameCallback callback) {
		renameCallback = callback;
	}
}
