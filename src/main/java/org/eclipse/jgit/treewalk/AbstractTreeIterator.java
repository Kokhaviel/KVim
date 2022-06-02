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

import org.eclipse.jgit.attributes.AttributesNode;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.util.Paths;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;

import static java.nio.charset.StandardCharsets.UTF_8;

public abstract class AbstractTreeIterator {

	protected static final int DEFAULT_PATH_SIZE = 128;
	protected static final byte[] zeroid = new byte[Constants.OBJECT_ID_LENGTH];
	public final AbstractTreeIterator parent;
	AbstractTreeIterator matches;
	protected AttributesNode attributesNode;
	int matchShift;
	protected int mode;
	protected byte[] path;
	protected final int pathOffset;
	protected int pathLen;

	protected AbstractTreeIterator() {
		parent = null;
		path = new byte[DEFAULT_PATH_SIZE];
		pathOffset = 0;
	}

	protected AbstractTreeIterator(String prefix) {
		parent = null;

		if(prefix != null && prefix.length() > 0) {
			final ByteBuffer b;

			b = UTF_8.encode(CharBuffer.wrap(prefix));
			pathLen = b.limit();
			path = new byte[Math.max(DEFAULT_PATH_SIZE, pathLen + 1)];
			b.get(path, 0, pathLen);
			if(path[pathLen - 1] != '/')
				path[pathLen++] = '/';
			pathOffset = pathLen;
		} else {
			path = new byte[DEFAULT_PATH_SIZE];
			pathOffset = 0;
		}
	}

	protected AbstractTreeIterator(byte[] prefix) {
		parent = null;

		if(prefix != null && prefix.length > 0) {
			pathLen = prefix.length;
			path = new byte[Math.max(DEFAULT_PATH_SIZE, pathLen + 1)];
			System.arraycopy(prefix, 0, path, 0, pathLen);
			if(path[pathLen - 1] != '/')
				path[pathLen++] = '/';
			pathOffset = pathLen;
		} else {
			path = new byte[DEFAULT_PATH_SIZE];
			pathOffset = 0;
		}
	}

	protected AbstractTreeIterator(AbstractTreeIterator p) {
		parent = p;
		path = p.path;
		pathOffset = p.pathLen + 1;

		if(pathOffset > path.length) {
			growPath(p.pathLen);
		}
		path[pathOffset - 1] = '/';
	}

	protected AbstractTreeIterator(final AbstractTreeIterator p,
								   final byte[] childPath, final int childPathOffset) {
		parent = p;
		path = childPath;
		pathOffset = childPathOffset;
	}

	protected void growPath(int len) {
		setPathCapacity(path.length << 1, len);
	}

	protected void ensurePathCapacity(int capacity, int len) {
		if(path.length >= capacity)
			return;
		final byte[] o = path;
		int newCapacity = o.length;
		while(newCapacity < capacity && newCapacity > 0)
			newCapacity <<= 1;
		setPathCapacity(newCapacity, len);
	}

	private void setPathCapacity(int capacity, int len) {
		final byte[] o = path;
		final byte[] n = new byte[capacity];
		System.arraycopy(o, 0, n, 0, len);
		for(AbstractTreeIterator p = this; p != null && p.path == o; p = p.parent)
			p.path = n;
	}

	public int pathCompare(AbstractTreeIterator p) {
		return pathCompare(p, p.mode);
	}

	int pathCompare(AbstractTreeIterator p, int pMode) {
		int cPos = alreadyMatch(this, p);
		return pathCompare(p.path, cPos, p.pathLen, pMode, cPos);
	}

	public boolean findFile(byte[] name) throws CorruptObjectException {
		for(; !eof(); next(1)) {
			int cmp = pathCompare(name, 0, name.length, 0, pathOffset);
			if(cmp == 0) {
				return true;
			} else if(cmp > 0) {
				return false;
			}
		}
		return false;
	}

	public int pathCompare(byte[] buf, int pos, int end, int pathMode) {
		return pathCompare(buf, pos, end, pathMode, 0);
	}

	private int pathCompare(byte[] b, int bPos, int bEnd, int bMode, int aPos) {
		return Paths.compare(
				path, aPos, pathLen, mode,
				b, bPos, bEnd, bMode);
	}

	private static int alreadyMatch(AbstractTreeIterator a,
									AbstractTreeIterator b) {
		for(; ; ) {
			final AbstractTreeIterator ap = a.parent;
			final AbstractTreeIterator bp = b.parent;
			if(ap == null || bp == null)
				return 0;
			if(ap.matches == bp.matches)
				return a.pathOffset;
			a = ap;
			b = bp;
		}
	}

	public boolean idEqual(AbstractTreeIterator otherIterator) {
		return ObjectId.equals(idBuffer(), idOffset(),
				otherIterator.idBuffer(), otherIterator.idOffset());
	}

	public abstract boolean hasId();

	public ObjectId getEntryObjectId() {
		return ObjectId.fromRaw(idBuffer(), idOffset());
	}

	public void getEntryObjectId(MutableObjectId out) {
		out.fromRaw(idBuffer(), idOffset());
	}

	public FileMode getEntryFileMode() {
		return FileMode.fromBits(mode);
	}

	public int getEntryRawMode() {
		return mode;
	}

	public String getEntryPathString() {
		return TreeWalk.pathOf(this);
	}

	public byte[] getEntryPathBuffer() {
		return path;
	}

	public int getEntryPathLength() {
		return pathLen;
	}

	public abstract byte[] idBuffer();

	public abstract int idOffset();

	public abstract AbstractTreeIterator createSubtreeIterator(
			ObjectReader reader) throws	IOException;

	public EmptyTreeIterator createEmptyTreeIterator() {
		return new EmptyTreeIterator(this);
	}

	public AbstractTreeIterator createSubtreeIterator(
			final ObjectReader reader, final MutableObjectId idBuffer)
			throws IOException {
		return createSubtreeIterator(reader);
	}

	public void reset() throws CorruptObjectException {
		while(!first())
			back(1);
	}

	public abstract boolean first();

	public abstract boolean eof();

	public abstract void next(int delta) throws CorruptObjectException;

	public abstract void back(int delta) throws CorruptObjectException;

	public void skip() throws CorruptObjectException {
		next(1);
	}

	public void stopWalk() {
	}

	protected boolean needsStopWalk() {
		return false;
	}

	public int getNameLength() {
		return pathLen - pathOffset;
	}

	public int getNameOffset() {
		return pathOffset;
	}

	public void getName(byte[] buffer, int offset) {
		System.arraycopy(path, pathOffset, buffer, offset, pathLen - pathOffset);
	}

	@SuppressWarnings("nls")
	@Override
	public String toString() {
		return getClass().getSimpleName() + "[" + getEntryPathString() + "]";
	}

	public boolean isWorkTree() {
		return false;
	}

	public void setDirCacheIterator(TreeWalk treeWalk, int i) {

	}
}
