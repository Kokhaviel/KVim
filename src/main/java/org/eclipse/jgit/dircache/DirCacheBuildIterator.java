/*
 * Copyright (C) 2008-2009, Google Inc.
 * Copyright (C) 2009, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.dircache;

import java.io.IOException;

import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;

public class DirCacheBuildIterator extends DirCacheIterator {
	private final DirCacheBuilder builder;

	public DirCacheBuildIterator(DirCacheBuilder dcb) {
		super(dcb.getDirCache());
		builder = dcb;
	}

	DirCacheBuildIterator(final DirCacheBuildIterator p,
			final DirCacheTree dct) {
		super(p, dct);
		builder = p.builder;
	}

	@Override
	public AbstractTreeIterator createSubtreeIterator(ObjectReader reader)
			throws IOException {
		if (currentSubtree == null)
			throw new IncorrectObjectTypeException(getEntryObjectId(),
					Constants.TYPE_TREE);
		return new DirCacheBuildIterator(this, currentSubtree);
	}

	@Override
	public void skip() throws CorruptObjectException {
		if (currentSubtree != null)
			builder.keep(ptr, currentSubtree.getEntrySpan());
		else
			builder.keep(ptr, 1);
		next(1);
	}

	@Override
	public void stopWalk() {
		final int cur = ptr;
		final int cnt = cache.getEntryCount();
		if (cur < cnt)
			builder.keep(cur, cnt - cur);
	}

	@Override
	protected boolean needsStopWalk() {
		return ptr < cache.getEntryCount();
	}
}
