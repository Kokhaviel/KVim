/*
 * Copyright (C) 2008, 2022 Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.revwalk;

import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.LargeObjectException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.filter.ObjectFilter;
import org.eclipse.jgit.util.RawParseUtils;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.requireNonNull;
import static org.eclipse.jgit.lib.Constants.*;

public class ObjectWalk extends RevWalk {
	private static final int ID_SZ = 20;
	private static final int TYPE_SHIFT = 12;
	private static final int TYPE_TREE = 16384 >>> TYPE_SHIFT;
	private static final int TYPE_SYMLINK = 40960 >>> TYPE_SHIFT;
	private static final int TYPE_FILE = 32768 >>> TYPE_SHIFT;
	private static final int TYPE_GITLINK = 57344 >>> TYPE_SHIFT;

	private static final int IN_PENDING = RevWalk.REWRITE;

	public interface VisitationPolicy {

		boolean shouldVisit(RevObject o);

		void visited(RevObject o);
	}

	public static final VisitationPolicy SIMPLE_VISITATION_POLICY =
			new VisitationPolicy() {
				@Override
				public boolean shouldVisit(RevObject o) {
					return (o.flags & SEEN) == 0;
				}

				@Override
				public void visited(RevObject o) {
					o.flags |= SEEN;
				}
			};

	private List<RevObject> rootObjects;
	private BlockObjQueue pendingObjects;
	private ObjectFilter objectFilter;
	private TreeVisit freeVisit;
	private TreeVisit currVisit;
	private byte[] pathBuf;
	private int pathLen;
	private boolean boundary;
	private VisitationPolicy visitationPolicy = SIMPLE_VISITATION_POLICY;

	public ObjectWalk(Repository repo) {
		this(repo.newObjectReader(), true);
	}

	public ObjectWalk(ObjectReader or) {
		this(or, false);
	}

	private ObjectWalk(ObjectReader or, boolean closeReader) {
		super(or, closeReader);
		setRetainBody(false);
		rootObjects = new ArrayList<>();
		pendingObjects = new BlockObjQueue();
		objectFilter = ObjectFilter.ALL;
		pathBuf = new byte[256];
	}

	@Deprecated
	public final ObjectReachabilityChecker createObjectReachabilityChecker()
			throws IOException {
		return reader.createObjectReachabilityChecker(this);
	}

	public void markStart(RevObject o) throws IOException {
		while(o instanceof RevTag) {
			addObject(o);
			o = ((RevTag) o).getObject();
			parseHeaders(o);
		}

		if(o instanceof RevCommit)
			super.markStart((RevCommit) o);
		else
			addObject(o);
	}

	public void markUninteresting(RevObject o) throws IOException {
		while(o instanceof RevTag) {
			o.flags |= UNINTERESTING;
			if(boundary)
				addObject(o);
			o = ((RevTag) o).getObject();
			parseHeaders(o);
		}

		if(o instanceof RevCommit)
			super.markUninteresting((RevCommit) o);
		else if(o instanceof RevTree)
			markTreeUninteresting((RevTree) o);
		else
			o.flags |= UNINTERESTING;

		if(o.getType() != OBJ_COMMIT && boundary)
			addObject(o);
	}

	@Override
	public void sort(RevSort s) {
		super.sort(s);
		boundary = hasRevSort(RevSort.BOUNDARY);
	}

	@Override
	public void sort(RevSort s, boolean use) {
		super.sort(s, use);
		boundary = hasRevSort(RevSort.BOUNDARY);
	}

	public void setObjectFilter(ObjectFilter newFilter) {
		assertNotStarted();
		objectFilter = newFilter != null ? newFilter : ObjectFilter.ALL;
	}

	public void setVisitationPolicy(VisitationPolicy policy) {
		assertNotStarted();
		visitationPolicy = requireNonNull(policy);
	}

	@Override
	public RevCommit next() throws IOException {
		for(; ; ) {
			final RevCommit r = super.next();
			if(r == null) {
				return null;
			}
			final RevTree t = r.getTree();
			if((r.flags & UNINTERESTING) != 0) {
				if(objectFilter.include(this, t)) {
					markTreeUninteresting(t);
				}
				if(boundary) {
					return r;
				}
				continue;
			}
			if(objectFilter.include(this, t)) {
				pendingObjects.add(t);
			}
			return r;
		}
	}

	public void skipTree() {
		if(currVisit != null) {
			currVisit.ptr = currVisit.buf.length;
		}
	}

	public RevObject nextObject() throws IOException {
		pathLen = 0;

		TreeVisit tv = currVisit;
		while(tv != null) {
			byte[] buf = tv.buf;
			for(int ptr = tv.ptr; ptr < buf.length; ) {
				int startPtr = ptr;
				ptr = findObjectId(buf, ptr);
				idBuffer.fromRaw(buf, ptr);
				ptr += ID_SZ;

				if(!objectFilter.include(this, idBuffer)) {
					continue;
				}

				RevObject obj = objects.get(idBuffer);
				if(obj != null && !visitationPolicy.shouldVisit(obj))
					continue;

				int mode = parseMode(buf, startPtr, ptr, tv);
				switch(mode >>> TYPE_SHIFT) {
					case TYPE_FILE:
					case TYPE_SYMLINK:
						if(obj == null) {
							obj = new RevBlob(idBuffer);
							visitationPolicy.visited(obj);
							objects.add(obj);
							return obj;
						}
						if(!(obj instanceof RevBlob))
							throw new IncorrectObjectTypeException(obj, OBJ_BLOB);
						visitationPolicy.visited(obj);
						if((obj.flags & UNINTERESTING) == 0)
							return obj;
						if(boundary)
							return obj;
						continue;

					case TYPE_TREE:
						if(obj == null) {
							obj = new RevTree(idBuffer);
							visitationPolicy.visited(obj);
							objects.add(obj);
							return pushTree(obj);
						}
						if(!(obj instanceof RevTree))
							throw new IncorrectObjectTypeException(obj, OBJ_TREE);
						visitationPolicy.visited(obj);
						if((obj.flags & UNINTERESTING) == 0)
							return pushTree(obj);
						if(boundary)
							return pushTree(obj);
						continue;

					case TYPE_GITLINK:
						continue;

					default:
						throw new CorruptObjectException(MessageFormat.format(
								JGitText.get().corruptObjectInvalidMode3,
								String.format("%o", mode),
								idBuffer.name(),
								RawParseUtils.decode(buf, tv.namePtr, tv.nameEnd),
								tv.obj));
				}
			}

			currVisit = tv.parent;
			releaseTreeVisit(tv);
			tv = currVisit;
		}

		for(; ; ) {
			RevObject o = pendingObjects.next();
			if(o == null) {
				return null;
			}
			if(!visitationPolicy.shouldVisit(o)) {
				continue;
			}
			visitationPolicy.visited(o);
			if((o.flags & UNINTERESTING) == 0 || boundary) {
				if(o instanceof RevTree) {
					assert currVisit == null;

					pushTree(o);
				}
				return o;
			}
		}
	}

	private static int findObjectId(byte[] buf, int ptr) {
		for(; ; ) {
			if(buf[++ptr] == 0) return ++ptr;
		}
	}

	private static int parseMode(byte[] buf, int startPtr, int recEndPtr, TreeVisit tv) {
		int mode = buf[startPtr] - '0';
		for(; ; ) {
			byte c = buf[++startPtr];
			if(' ' == c)
				break;
			mode <<= 3;
			mode += c - '0';

			c = buf[++startPtr];
			if(' ' == c)
				break;
			mode <<= 3;
			mode += c - '0';

			c = buf[++startPtr];
			if(' ' == c)
				break;
			mode <<= 3;
			mode += c - '0';

			c = buf[++startPtr];
			if(' ' == c)
				break;
			mode <<= 3;
			mode += c - '0';

			c = buf[++startPtr];
			if(' ' == c)
				break;
			mode <<= 3;
			mode += c - '0';

			c = buf[++startPtr];
			if(' ' == c)
				break;
			mode <<= 3;
			mode += c - '0';

			c = buf[++startPtr];
			if(' ' == c)
				break;
			mode <<= 3;
			mode += c - '0';
		}

		tv.ptr = recEndPtr;
		tv.namePtr = startPtr + 1;
		tv.nameEnd = recEndPtr - (ID_SZ + 1);
		return mode;
	}

	public void checkConnectivity() throws IOException {
		for(; ; ) {
			final RevCommit c = next();
			if(c == null)
				break;
		}
		for(; ; ) {
			final RevObject o = nextObject();
			if(o == null)
				break;
			if(o instanceof RevBlob && !reader.has(o))
				throw new MissingObjectException(o, OBJ_BLOB);
		}
	}

	public String getPathString() {
		if(pathLen == 0) {
			pathLen = updatePathBuf(currVisit);
			if(pathLen == 0)
				return null;
		}
		return RawParseUtils.decode(pathBuf, 0, pathLen);
	}

	public int getTreeDepth() {
		if(currVisit == null) {
			return 0;
		}
		return currVisit.depth;
	}

	public int getPathHashCode() {
		TreeVisit tv = currVisit;
		if(tv == null)
			return 0;

		int nameEnd = tv.nameEnd;
		if(nameEnd == 0) {
			tv = tv.parent;
			if(tv == null)
				return 0;
			nameEnd = tv.nameEnd;
		}

		byte[] buf;
		int ptr;

		if(16 <= (nameEnd - tv.namePtr)) {
			buf = tv.buf;
			ptr = nameEnd - 16;
		} else {
			nameEnd = pathLen;
			if(nameEnd == 0) {
				nameEnd = updatePathBuf(currVisit);
				pathLen = nameEnd;
			}
			buf = pathBuf;
			ptr = Math.max(0, nameEnd - 16);
		}

		int hash = 0;
		for(; ptr < nameEnd; ptr++) {
			byte c = buf[ptr];
			if(c != ' ')
				hash = (hash >>> 2) + (c << 24);
		}
		return hash;
	}

	public byte[] getPathBuffer() {
		if(pathLen == 0)
			pathLen = updatePathBuf(currVisit);
		return pathBuf;
	}

	public int getPathLength() {
		if(pathLen == 0)
			pathLen = updatePathBuf(currVisit);
		return pathLen;
	}

	private int updatePathBuf(TreeVisit tv) {
		if(tv == null)
			return 0;

		int nameEnd = tv.nameEnd;
		if(nameEnd == 0)
			return updatePathBuf(tv.parent);

		int ptr = tv.pathLen;
		if(ptr == 0) {
			ptr = updatePathBuf(tv.parent);
			if(ptr == pathBuf.length)
				growPathBuf(ptr);
			if(ptr != 0)
				pathBuf[ptr++] = '/';
			tv.pathLen = ptr;
		}

		int namePtr = tv.namePtr;
		int nameLen = nameEnd - namePtr;
		int end = ptr + nameLen;
		while(pathBuf.length < end)
			growPathBuf(ptr);
		System.arraycopy(tv.buf, namePtr, pathBuf, ptr, nameLen);
		return end;
	}

	private void growPathBuf(int ptr) {
		byte[] newBuf = new byte[pathBuf.length << 1];
		System.arraycopy(pathBuf, 0, newBuf, 0, ptr);
		pathBuf = newBuf;
	}

	@Override
	public void dispose() {
		super.dispose();
		pendingObjects = new BlockObjQueue();
		currVisit = null;
		freeVisit = null;
	}

	@Override
	protected void reset(int retainFlags) {
		super.reset(retainFlags);

		for(RevObject obj : rootObjects)
			obj.flags &= ~IN_PENDING;

		rootObjects = new ArrayList<>();
		pendingObjects = new BlockObjQueue();
		currVisit = null;
		freeVisit = null;
	}

	private void addObject(RevObject o) {
		if((o.flags & IN_PENDING) == 0) {
			o.flags |= IN_PENDING;
			rootObjects.add(o);
			pendingObjects.add(o);
		}
	}

	private void markTreeUninteresting(RevTree tree) throws IOException {
		if((tree.flags & UNINTERESTING) != 0)
			return;
		tree.flags |= UNINTERESTING;

		byte[] raw = reader.open(tree, OBJ_TREE).getCachedBytes();
		for(int ptr = 0; ptr < raw.length; ) {
			byte c = raw[ptr];
			int mode = c - '0';
			for(; ; ) {
				c = raw[++ptr];
				if(' ' == c)
					break;
				mode <<= 3;
				mode += c - '0';
			}
			ptr++;

			switch(mode >>> TYPE_SHIFT) {
				case TYPE_FILE:
				case TYPE_SYMLINK:
					idBuffer.fromRaw(raw, ptr);
					lookupBlob(idBuffer).flags |= UNINTERESTING;
					break;

				case TYPE_TREE:
					idBuffer.fromRaw(raw, ptr);
					markTreeUninteresting(lookupTree(idBuffer));
					break;

				case TYPE_GITLINK:
					break;

				default:
					idBuffer.fromRaw(raw, ptr);
					throw new CorruptObjectException(MessageFormat.format(
							JGitText.get().corruptObjectInvalidMode3,
							String.format("%o", mode),
							idBuffer.name(), "", tree));
			}
			ptr += ID_SZ;
		}
	}

	private RevObject pushTree(RevObject obj) throws LargeObjectException, IOException {
		TreeVisit tv = freeVisit;
		if(tv != null) {
			freeVisit = tv.parent;
			tv.ptr = 0;
			tv.namePtr = 0;
			tv.nameEnd = 0;
			tv.pathLen = 0;
		} else {
			tv = new TreeVisit();
		}
		tv.obj = obj;
		tv.buf = reader.open(obj, OBJ_TREE).getCachedBytes();
		tv.parent = currVisit;
		currVisit = tv;
		if(tv.parent == null) {
			tv.depth = 1;
		} else {
			tv.depth = tv.parent.depth + 1;
		}

		return obj;
	}

	private void releaseTreeVisit(TreeVisit tv) {
		tv.buf = null;
		tv.parent = freeVisit;
		freeVisit = tv;
	}

	private static class TreeVisit {
		TreeVisit parent;
		RevObject obj;
		byte[] buf;
		int ptr;
		int namePtr;
		int nameEnd;
		int pathLen;
		int depth;
	}
}
