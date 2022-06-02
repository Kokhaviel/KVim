/*
 * Copyright (C) 2008-2009, Google Inc.
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.dircache;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.jgit.lib.FileMode.TREE;
import static org.eclipse.jgit.lib.TreeFormatter.entrySize;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Comparator;

import org.eclipse.jgit.errors.UnmergedPathException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.util.MutableInteger;
import org.eclipse.jgit.util.RawParseUtils;

public class DirCacheTree {
	private static final byte[] NO_NAME = {};

	private static final DirCacheTree[] NO_CHILDREN = {};

	private static final Comparator<DirCacheTree> TREE_CMP = (DirCacheTree o1,
			DirCacheTree o2) -> {
		final byte[] a = o1.encodedName;
		final byte[] b = o2.encodedName;
		final int aLen = a.length;
		final int bLen = b.length;
		int cPos;
		for (cPos = 0; cPos < aLen && cPos < bLen; cPos++) {
			final int cmp = (a[cPos] & 0xff) - (b[cPos] & 0xff);
			if (cmp != 0) {
				return cmp;
			}
		}
		if (aLen == bLen) {
			return 0;
		}
		if (aLen < bLen) {
			return '/' - (b[cPos] & 0xff);
		}
		return (a[cPos] & 0xff) - '/';
	};

	byte[] encodedName;
	private int entrySpan;
	private ObjectId id;
	private DirCacheTree[] children;
	private int childCnt;

	DirCacheTree() {
		encodedName = NO_NAME;
		children = NO_CHILDREN;
		childCnt = 0;
		entrySpan = -1;
	}

	private DirCacheTree(final DirCacheTree myParent, final byte[] path,
			final int pathOff, final int pathLen) {
		encodedName = new byte[pathLen];
		System.arraycopy(path, pathOff, encodedName, 0, pathLen);
		children = NO_CHILDREN;
		childCnt = 0;
		entrySpan = -1;
	}

	DirCacheTree(final byte[] in, final MutableInteger off,
			final DirCacheTree myParent) {

		int ptr = RawParseUtils.next(in, off.value, '\0');
		final int nameLen = ptr - off.value - 1;
		if (nameLen > 0) {
			encodedName = new byte[nameLen];
			System.arraycopy(in, off.value, encodedName, 0, nameLen);
		} else
			encodedName = NO_NAME;

		entrySpan = RawParseUtils.parseBase10(in, ptr, off);
		final int subcnt = RawParseUtils.parseBase10(in, off.value, off);
		off.value = RawParseUtils.next(in, off.value, '\n');

		if (entrySpan >= 0) {
			id = ObjectId.fromRaw(in, off.value);
			off.value += Constants.OBJECT_ID_LENGTH;
		}

		if (subcnt > 0) {
			boolean alreadySorted = true;
			children = new DirCacheTree[subcnt];
			for (int i = 0; i < subcnt; i++) {
				children[i] = new DirCacheTree(in, off, this);

				if (alreadySorted && i > 0
						&& TREE_CMP.compare(children[i - 1], children[i]) > 0)
					alreadySorted = false;
			}
			if (!alreadySorted)
				Arrays.sort(children, 0, subcnt, TREE_CMP);
		} else {
			children = NO_CHILDREN;
		}
		childCnt = subcnt;
	}

	void write(byte[] tmp, OutputStream os) throws IOException {
		int ptr = tmp.length;
		tmp[--ptr] = '\n';
		ptr = RawParseUtils.formatBase10(tmp, ptr, childCnt);
		tmp[--ptr] = ' ';
		ptr = RawParseUtils.formatBase10(tmp, ptr, isValid() ? entrySpan : -1);
		tmp[--ptr] = 0;

		os.write(encodedName);
		os.write(tmp, ptr, tmp.length - ptr);
		if (isValid()) {
			id.copyRawTo(tmp, 0);
			os.write(tmp, 0, Constants.OBJECT_ID_LENGTH);
		}
		for (int i = 0; i < childCnt; i++)
			children[i].write(tmp, os);
	}

	public boolean isValid() {
		return id != null;
	}

	public int getEntrySpan() {
		return entrySpan;
	}

	public int getChildCount() {
		return childCnt;
	}

	public DirCacheTree getChild(int i) {
		return children[i];
	}

	public ObjectId getObjectId() {
		return id;
	}

	public String getNameString() {
		final ByteBuffer bb = ByteBuffer.wrap(encodedName);
		return UTF_8.decode(bb).toString();
	}

	ObjectId writeTree(final DirCacheEntry[] cache, int cIdx,
			final int pathOffset, final ObjectInserter ow)
			throws IOException {
		if (id == null) {
			final int endIdx = cIdx + entrySpan;
			final TreeFormatter fmt = new TreeFormatter(computeSize(cache,
					cIdx, pathOffset, ow));
			int childIdx = 0;
			int entryIdx = cIdx;

			while (entryIdx < endIdx) {
				final DirCacheEntry e = cache[entryIdx];
				final byte[] ep = e.path;
				if (childIdx < childCnt) {
					final DirCacheTree st = children[childIdx];
					if (st.contains(ep, pathOffset, ep.length)) {
						fmt.append(st.encodedName, TREE, st.id);
						entryIdx += st.entrySpan;
						childIdx++;
						continue;
					}
				}

				fmt.append(ep, pathOffset, ep.length - pathOffset, e
						.getFileMode(), e.idBuffer(), e.idOffset());
				entryIdx++;
			}

			id = ow.insert(fmt);
		}
		return id;
	}

	private int computeSize(final DirCacheEntry[] cache, int cIdx,
			final int pathOffset, final ObjectInserter ow)
			throws IOException {
		final int endIdx = cIdx + entrySpan;
		int childIdx = 0;
		int entryIdx = cIdx;
		int size = 0;

		while (entryIdx < endIdx) {
			final DirCacheEntry e = cache[entryIdx];
			if (e.getStage() != 0)
				throw new UnmergedPathException(e);

			final byte[] ep = e.path;
			if (childIdx < childCnt) {
				final DirCacheTree st = children[childIdx];
				if (st.contains(ep, pathOffset, ep.length)) {
					final int stOffset = pathOffset + st.nameLength() + 1;
					st.writeTree(cache, entryIdx, stOffset, ow);

					size += entrySize(TREE, st.nameLength());

					entryIdx += st.entrySpan;
					childIdx++;
					continue;
				}
			}

			size += entrySize(e.getFileMode(), ep.length - pathOffset);
			entryIdx++;
		}

		return size;
	}

	final int nameLength() {
		return encodedName.length;
	}

	final boolean contains(byte[] a, int aOff, int aLen) {
		final byte[] e = encodedName;
		final int eLen = e.length;
		for (int eOff = 0; eOff < eLen && aOff < aLen; eOff++, aOff++)
			if (e[eOff] != a[aOff])
				return false;
		if (aOff >= aLen)
			return false;
		return a[aOff] == '/';
	}

	void validate(final DirCacheEntry[] cache, final int cCnt, int cIdx,
			final int pathOff) {
		if (entrySpan >= 0 && cIdx + entrySpan <= cCnt) {
			return;
		}

		entrySpan = 0;
		if (cCnt == 0) {
			return;
		}

		final byte[] firstPath = cache[cIdx].path;
		int stIdx = 0;
		while (cIdx < cCnt) {
			final byte[] currPath = cache[cIdx].path;
			if (pathOff > 0 && !peq(firstPath, currPath, pathOff)) {
				break;
			}

			DirCacheTree st = stIdx < childCnt ? children[stIdx] : null;
			final int cc = namecmp(currPath, pathOff, st);
			if (cc > 0) {
				removeChild(stIdx);
				continue;
			}

			if (cc < 0) {
				final int p = slash(currPath, pathOff);
				if (p < 0) {
					cIdx++;
					entrySpan++;
					continue;
				}

				st = new DirCacheTree(this, currPath, pathOff, p - pathOff);
				insertChild(stIdx, st);
			}

			assert(st != null);
			st.validate(cache, cCnt, cIdx, pathOff + st.nameLength() + 1);
			cIdx += st.entrySpan;
			entrySpan += st.entrySpan;
			stIdx++;
		}

		while (stIdx < childCnt) removeChild(childCnt - 1);
	}

	private void insertChild(int stIdx, DirCacheTree st) {
		final DirCacheTree[] c = children;
		if (childCnt + 1 <= c.length) {
			if (stIdx < childCnt)
				System.arraycopy(c, stIdx, c, stIdx + 1, childCnt - stIdx);
			c[stIdx] = st;
			childCnt++;
			return;
		}

		final int n = c.length;
		final DirCacheTree[] a = new DirCacheTree[n + 1];
		if (stIdx > 0)
			System.arraycopy(c, 0, a, 0, stIdx);
		a[stIdx] = st;
		if (stIdx < n)
			System.arraycopy(c, stIdx, a, stIdx + 1, n - stIdx);
		children = a;
		childCnt++;
	}

	private void removeChild(int stIdx) {
		final int n = --childCnt;
		if (stIdx < n)
			System.arraycopy(children, stIdx + 1, children, stIdx, n - stIdx);
		children[n] = null;
	}

	static boolean peq(byte[] a, byte[] b, int aLen) {
		if (b.length < aLen)
			return false;
		for (aLen--; aLen >= 0; aLen--)
			if (a[aLen] != b[aLen])
				return false;
		return true;
	}

	private static int namecmp(byte[] a, int aPos, DirCacheTree ct) {
		if (ct == null)
			return -1;
		final byte[] b = ct.encodedName;
		final int aLen = a.length;
		final int bLen = b.length;
		int bPos = 0;
		for (; aPos < aLen && bPos < bLen; aPos++, bPos++) {
			final int cmp = (a[aPos] & 0xff) - (b[bPos] & 0xff);
			if (cmp != 0)
				return cmp;
		}
		if (bPos == bLen)
			return a[aPos] == '/' ? 0 : -1;
		return aLen - bLen;
	}

	private static int slash(byte[] a, int aPos) {
		final int aLen = a.length;
		for (; aPos < aLen; aPos++)
			if (a[aPos] == '/')
				return aPos;
		return -1;
	}

	@Override
	public String toString() {
		return getNameString();
	}
}
