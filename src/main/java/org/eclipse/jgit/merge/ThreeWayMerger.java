/*
 * Copyright (C) 2009, Google Inc.
 * Copyright (C) 2012, Research In Motion Limited and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.merge;

import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;

import java.io.IOException;

public abstract class ThreeWayMerger extends Merger {
	private RevTree baseTree;
	private ObjectId baseCommitId;

	protected ThreeWayMerger(Repository local) {
		super(local);
	}

	protected ThreeWayMerger(ObjectInserter inserter) {
		super(inserter);
	}

	public void setBase(AnyObjectId id) throws IOException {
		if(id != null) {
			baseTree = walk.parseTree(id);
		} else {
			baseTree = null;
		}
	}

	@Override
	public boolean merge(AnyObjectId... tips) throws IOException {
		if(tips.length != 2)
			return false;
		return super.merge(tips);
	}

	@Override
	public ObjectId getBaseCommitId() {
		return baseCommitId;
	}

	protected AbstractTreeIterator mergeBase() throws IOException {
		if(baseTree != null) {
			return openTree(baseTree);
		}
		RevCommit baseCommit = (baseCommitId != null) ? walk
				.parseCommit(baseCommitId) : getBaseCommit(sourceCommits[0],
				sourceCommits[1]);
		if(baseCommit == null) {
			baseCommitId = null;
			return new EmptyTreeIterator();
		}
		baseCommitId = baseCommit.toObjectId();
		return openTree(baseCommit.getTree());
	}
}
