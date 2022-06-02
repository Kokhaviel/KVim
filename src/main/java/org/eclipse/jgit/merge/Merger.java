/*
 * Copyright (C) 2008-2013, Google Inc.
 * Copyright (C) 2016, Laurent Delaigue <laurent.delaigue@obeo.fr> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.merge;

import java.io.IOException;
import java.text.MessageFormat;

import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.NoMergeBaseException;
import org.eclipse.jgit.errors.NoMergeBaseException.MergeBaseFailureReason;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;

public abstract class Merger {

	@Nullable
	protected final Repository db;
	protected ObjectReader reader;
	protected RevWalk walk;
	private final ObjectInserter inserter;
	protected RevObject[] sourceObjects;
	protected RevCommit[] sourceCommits;
	protected RevTree[] sourceTrees;

	protected ProgressMonitor monitor = NullProgressMonitor.INSTANCE;

	protected Merger(Repository local) {
		if(local == null) {
			throw new NullPointerException(JGitText.get().repositoryIsRequired);
		}
		db = local;
		inserter = local.newObjectInserter();
		reader = inserter.newReader();
		walk = new RevWalk(reader);
	}

	protected Merger(ObjectInserter oi) {
		db = null;
		inserter = oi;
		reader = oi.newReader();
		walk = new RevWalk(reader);
	}

	@Nullable
	public Repository getRepository() {
		return db;
	}

	protected Repository nonNullRepo() {
		if(db == null) {
			throw new NullPointerException(JGitText.get().repositoryIsRequired);
		}
		return db;
	}

	public ObjectInserter getObjectInserter() {
		return inserter;
	}

	public boolean merge(AnyObjectId... tips) throws IOException {
		return merge(true, tips);
	}

	public boolean merge(boolean flush, AnyObjectId... tips)
			throws IOException {
		sourceObjects = new RevObject[tips.length];
		for(int i = 0; i < tips.length; i++)
			sourceObjects[i] = walk.parseAny(tips[i]);

		sourceCommits = new RevCommit[sourceObjects.length];
		for(int i = 0; i < sourceObjects.length; i++) {
			try {
				sourceCommits[i] = walk.parseCommit(sourceObjects[i]);
			} catch(IncorrectObjectTypeException err) {
				sourceCommits[i] = null;
			}
		}

		sourceTrees = new RevTree[sourceObjects.length];
		for(int i = 0; i < sourceObjects.length; i++)
			sourceTrees[i] = walk.parseTree(sourceObjects[i]);

		try {
			boolean ok = mergeImpl();
			if(ok && flush)
				inserter.flush();
			return ok;
		} finally {
			if(flush)
				inserter.close();
			reader.close();
		}
	}

	public abstract ObjectId getBaseCommitId();

	protected RevCommit getBaseCommit(RevCommit a, RevCommit b) throws IOException {
		walk.reset();
		walk.setRevFilter(RevFilter.MERGE_BASE);
		walk.markStart(a);
		walk.markStart(b);
		final RevCommit base = walk.next();
		if(base == null)
			return null;
		final RevCommit base2 = walk.next();
		if(base2 != null) {
			throw new NoMergeBaseException(
					MergeBaseFailureReason.MULTIPLE_MERGE_BASES_NOT_SUPPORTED,
					MessageFormat.format(
							JGitText.get().multipleMergeBasesFor, a.name(), b.name(),
							base.name(), base2.name()));
		}
		return base;
	}

	protected AbstractTreeIterator openTree(AnyObjectId treeId) throws IOException {
		return new CanonicalTreeParser(null, reader, treeId);
	}

	protected abstract boolean mergeImpl() throws IOException;

	public abstract ObjectId getResultTreeId();

	public void setProgressMonitor(ProgressMonitor monitor) {
		if(monitor == null) {
			this.monitor = NullProgressMonitor.INSTANCE;
		} else {
			this.monitor = monitor;
		}
	}
}
