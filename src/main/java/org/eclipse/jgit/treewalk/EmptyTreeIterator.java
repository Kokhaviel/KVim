/*
 * Copyright (C) 2008-2009, Google Inc.
 * Copyright (C) 2007, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.treewalk;

import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;

public class EmptyTreeIterator extends AbstractTreeIterator {

	public EmptyTreeIterator() {
	}

	EmptyTreeIterator(AbstractTreeIterator p) {
		super(p);
		pathLen = pathOffset;
	}

	public EmptyTreeIterator(final AbstractTreeIterator p,
							 final byte[] childPath, final int childPathOffset) {
		super(p, childPath, childPathOffset);
		pathLen = childPathOffset - 1;
	}

	@Override
	public AbstractTreeIterator createSubtreeIterator(ObjectReader reader) {
		return new EmptyTreeIterator(this);
	}

	@Override
	public boolean hasId() {
		return false;
	}

	@Override
	public ObjectId getEntryObjectId() {
		return ObjectId.zeroId();
	}

	@Override
	public byte[] idBuffer() {
		return zeroid;
	}

	@Override
	public int idOffset() {
		return 0;
	}

	@Override
	public void reset() {
	}

	@Override
	public boolean first() {
		return true;
	}

	@Override
	public boolean eof() {
		return true;
	}

	@Override
	public void next(int delta) throws CorruptObjectException {
	}

	@Override
	public void back(int delta) {
	}

	@Override
	public void stopWalk() {
		if(parent != null)
			parent.stopWalk();
	}

	@Override
	protected boolean needsStopWalk() {
		return parent != null && parent.needsStopWalk();
	}
}
