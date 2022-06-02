/*
 * Copyright (C) 2007, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org>
 * Copyright (C) 2014, Gustaf Lundh <gustaf.lundh@sonymobile.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.revwalk;

import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.LargeObjectException;
import org.eclipse.jgit.errors.RevWalkException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.eclipse.jgit.util.References;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.*;

public class RevWalk implements Iterable<RevCommit>, AutoCloseable {
	private static final int MB = 1 << 20;

	static final int PARSED = 1;
	static final int SEEN = 1 << 1;
	static final int UNINTERESTING = 1 << 2;
	static final int REWRITE = 1 << 3;
	static final int TEMP_MARK = 1 << 4;
	static final int TOPO_DELAY = 1 << 5;
	static final int TOPO_QUEUED = 1 << 6;
	static final int RESERVED_FLAGS = 7;

	private static final int APP_FLAGS = -(1 << RESERVED_FLAGS);

	final ObjectReader reader;
	private final boolean closeReader;
	final MutableObjectId idBuffer;
	ObjectIdOwnerMap<RevObject> objects;
	int freeFlags = APP_FLAGS;
	private int delayFreeFlags;
	private int retainOnReset;
	int carryFlags = UNINTERESTING;
	final ArrayList<RevCommit> roots;
	AbstractRevQueue queue;
	Generator pending;
	private final EnumSet<RevSort> sorting;
	private RevFilter filter;
	private TreeFilter treeFilter;
	private boolean retainBody = true;
	private boolean firstParent;
	boolean shallowCommitsInitialized;

	private enum GetMergedIntoStrategy {
		RETURN_ON_FIRST_FOUND,
		RETURN_ON_FIRST_NOT_FOUND,
		EVALUATE_ALL
	}

	public RevWalk(Repository repo) {
		this(repo.newObjectReader(), true);
	}

	public RevWalk(ObjectReader or) {
		this(or, false);
	}

	RevWalk(ObjectReader or, boolean closeReader) {
		reader = or;
		idBuffer = new MutableObjectId();
		objects = new ObjectIdOwnerMap<>();
		roots = new ArrayList<>();
		queue = new DateRevQueue(false);
		pending = new StartGenerator(this);
		sorting = EnumSet.of(RevSort.NONE);
		filter = RevFilter.ALL;
		treeFilter = TreeFilter.ALL;
		this.closeReader = closeReader;
	}

	public ObjectReader getObjectReader() {
		return reader;
	}

	@Deprecated
	public final ReachabilityChecker createReachabilityChecker()
			throws IOException {
		return reader.createReachabilityChecker(this);
	}

	@Override
	public void close() {
		if(closeReader) {
			reader.close();
		}
	}

	public void markStart(RevCommit c) throws IOException {
		if((c.flags & SEEN) != 0)
			return;
		if((c.flags & PARSED) == 0)
			c.parseHeaders(this);
		c.flags |= SEEN;
		roots.add(c);
		queue.add(c);
	}

	public void markStart(Collection<RevCommit> list)
			throws IOException {
		for(RevCommit c : list)
			markStart(c);
	}

	public void markUninteresting(RevCommit c)
			throws
			IOException {
		c.flags |= UNINTERESTING;
		carryFlagsImpl(c);
		markStart(c);
	}

	public boolean isMergedInto(RevCommit base, RevCommit tip)
			throws IOException {
		final RevFilter oldRF = filter;
		final TreeFilter oldTF = treeFilter;
		try {
			finishDelayedFreeFlags();
			reset(~freeFlags & APP_FLAGS);
			filter = RevFilter.MERGE_BASE;
			treeFilter = TreeFilter.ALL;
			markStart(tip);
			markStart(base);
			RevCommit mergeBase;
			while((mergeBase = next()) != null) {
				if(References.isSameObject(mergeBase, base)) {
					return true;
				}
			}
			return false;
		} finally {
			filter = oldRF;
			treeFilter = oldTF;
		}
	}

	public List<Ref> getMergedInto(RevCommit commit, Collection<Ref> refs,
								   ProgressMonitor monitor) throws IOException {
		return getMergedInto(commit, refs,
				GetMergedIntoStrategy.EVALUATE_ALL,
				monitor);
	}

	private List<Ref> getMergedInto(RevCommit needle, Collection<Ref> haystacks,
									Enum returnStrategy, ProgressMonitor monitor) throws IOException {
		List<Ref> result = new ArrayList<>();
		List<RevCommit> uninteresting = new ArrayList<>();
		List<RevCommit> marked = new ArrayList<>();
		RevFilter oldRF = filter;
		TreeFilter oldTF = treeFilter;
		try {
			finishDelayedFreeFlags();
			reset(~freeFlags & APP_FLAGS);
			filter = RevFilter.ALL;
			treeFilter = TreeFilter.ALL;
			for(Ref r : haystacks) {
				if(monitor.isCancelled()) {
					return result;
				}
				monitor.update(1);
				RevObject o = peel(parseAny(r.getObjectId()));
				if(!(o instanceof RevCommit)) {
					continue;
				}
				RevCommit c = (RevCommit) o;
				reset(UNINTERESTING | TEMP_MARK);
				markStart(c);
				boolean commitFound = false;
				RevCommit next;
				while((next = next()) != null) {
					if(References.isSameObject(next, needle)
							|| (next.flags & TEMP_MARK) != 0) {
						result.add(r);
						if(returnStrategy == GetMergedIntoStrategy.RETURN_ON_FIRST_FOUND) {
							return result;
						}
						commitFound = true;
						c.flags |= TEMP_MARK;
						marked.add(c);
						break;
					}
				}
				if(!commitFound) {
					markUninteresting(c);
					uninteresting.add(c);
					if(returnStrategy == GetMergedIntoStrategy.RETURN_ON_FIRST_NOT_FOUND) {
						return result;
					}
				}
			}
		} finally {
			roots.addAll(uninteresting);
			filter = oldRF;
			treeFilter = oldTF;
			for(RevCommit c : marked) {
				c.flags &= ~TEMP_MARK;
			}
		}
		return result;
	}

	public RevCommit next() throws IOException {
		return pending.next();
	}

	public boolean hasRevSort(RevSort sort) {
		return sorting.contains(sort);
	}

	public void sort(RevSort s) {
		assertNotStarted();
		sorting.clear();
		sorting.add(s);
	}

	public void sort(RevSort s, boolean use) {
		assertNotStarted();
		if(use)
			sorting.add(s);
		else
			sorting.remove(s);

		if(sorting.size() > 1)
			sorting.remove(RevSort.NONE);
		else if(sorting.isEmpty())
			sorting.add(RevSort.NONE);
	}

	@NonNull
	public RevFilter getRevFilter() {
		return filter;
	}

	public void setRevFilter(RevFilter newFilter) {
		assertNotStarted();
		filter = newFilter != null ? newFilter : RevFilter.ALL;
	}

	@NonNull
	public TreeFilter getTreeFilter() {
		return treeFilter;
	}

	public void setTreeFilter(TreeFilter newFilter) {
		assertNotStarted();
		treeFilter = newFilter != null ? newFilter : TreeFilter.ALL;
	}

	boolean getRewriteParents() {
		return true;
	}

	public boolean isRetainBody() {
		return retainBody;
	}

	public void setRetainBody(boolean retain) {
		retainBody = retain;
	}

	public boolean isFirstParent() {
		return firstParent;
	}

	public void setFirstParent(boolean enable) {
		assertNotStarted();
		assertNoCommitsMarkedStart();
		firstParent = enable;
		queue = new DateRevQueue(firstParent);
		pending = new StartGenerator(this);
	}

	@NonNull
	public RevBlob lookupBlob(AnyObjectId id) {
		RevBlob c = (RevBlob) objects.get(id);
		if(c == null) {
			c = new RevBlob(id);
			objects.add(c);
		}
		return c;
	}

	@NonNull
	public RevTree lookupTree(AnyObjectId id) {
		RevTree c = (RevTree) objects.get(id);
		if(c == null) {
			c = new RevTree(id);
			objects.add(c);
		}
		return c;
	}

	@NonNull
	public RevCommit lookupCommit(AnyObjectId id) {
		RevCommit c = (RevCommit) objects.get(id);
		if(c == null) {
			c = createCommit(id);
			objects.add(c);
		}
		return c;
	}

	@NonNull
	public RevTag lookupTag(AnyObjectId id) {
		RevTag c = (RevTag) objects.get(id);
		if(c == null) {
			c = new RevTag(id);
			objects.add(c);
		}
		return c;
	}

	@NonNull
	public RevObject lookupAny(AnyObjectId id, int type) {
		RevObject r = objects.get(id);
		if(r == null) {
			switch(type) {
				case Constants.OBJ_COMMIT:
					r = createCommit(id);
					break;
				case Constants.OBJ_TREE:
					r = new RevTree(id);
					break;
				case Constants.OBJ_BLOB:
					r = new RevBlob(id);
					break;
				case Constants.OBJ_TAG:
					r = new RevTag(id);
					break;
				default:
					throw new IllegalArgumentException(MessageFormat.format(
							JGitText.get().invalidGitType, type));
			}
			objects.add(r);
		}
		return r;
	}

	public RevObject lookupOrNull(AnyObjectId id) {
		return objects.get(id);
	}

	@NonNull
	public RevCommit parseCommit(AnyObjectId id)
			throws IOException {
		RevObject c = peel(parseAny(id));
		if(!(c instanceof RevCommit))
			throw new IncorrectObjectTypeException(id.toObjectId(),
					Constants.TYPE_COMMIT);
		return (RevCommit) c;
	}

	@NonNull
	public RevTree parseTree(AnyObjectId id)
			throws IOException {
		RevObject c = peel(parseAny(id));

		final RevTree t;
		if(c instanceof RevCommit)
			t = ((RevCommit) c).getTree();
		else if(!(c instanceof RevTree))
			throw new IncorrectObjectTypeException(id.toObjectId(),
					Constants.TYPE_TREE);
		else
			t = (RevTree) c;
		parseHeaders(t);
		return t;
	}

	@NonNull
	public RevObject parseAny(AnyObjectId id)
			throws IOException {
		RevObject r = objects.get(id);
		if(r == null)
			r = parseNew(id, reader.open(id));
		else
			parseHeaders(r);
		return r;
	}

	private RevObject parseNew(AnyObjectId id, ObjectLoader ldr)
			throws LargeObjectException,
			IOException {
		RevObject r;
		int type = ldr.getType();
		switch(type) {
			case Constants.OBJ_COMMIT: {
				final RevCommit c = createCommit(id);
				c.parseCanonical(this, getCachedBytes(c, ldr));
				r = c;
				break;
			}
			case Constants.OBJ_TREE: {
				r = new RevTree(id);
				r.flags |= PARSED;
				break;
			}
			case Constants.OBJ_BLOB: {
				r = new RevBlob(id);
				r.flags |= PARSED;
				break;
			}
			case Constants.OBJ_TAG: {
				final RevTag t = new RevTag(id);
				t.parseCanonical(this, getCachedBytes(t, ldr));
				r = t;
				break;
			}
			default:
				throw new IllegalArgumentException(MessageFormat.format(
						JGitText.get().badObjectType, type));
		}
		objects.add(r);
		return r;
	}

	byte[] getCachedBytes(RevObject obj) throws LargeObjectException,
			IOException {
		return getCachedBytes(obj, reader.open(obj, obj.getType()));
	}

	byte[] getCachedBytes(RevObject obj, ObjectLoader ldr)
			throws LargeObjectException, IOException {
		try {
			return ldr.getCachedBytes(5 * MB);
		} catch(LargeObjectException tooBig) {
			tooBig.setObjectId(obj);
			throw tooBig;
		}
	}

	public <T extends ObjectId> AsyncRevObjectQueue parseAny(
			Iterable<T> objectIds, boolean reportMissing) {
		List<T> need = new ArrayList<>();
		List<RevObject> have = new ArrayList<>();
		for(T id : objectIds) {
			RevObject r = objects.get(id);
			if(r != null && (r.flags & PARSED) != 0)
				have.add(r);
			else
				need.add(id);
		}

		final Iterator<RevObject> objItr = have.iterator();
		if(need.isEmpty()) {
			return new AsyncRevObjectQueue() {
				@Override
				public RevObject next() {
					return objItr.hasNext() ? objItr.next() : null;
				}

				@Override
				public boolean cancel(boolean mayInterruptIfRunning) {
					return true;
				}

				@Override
				public void release() {
				}
			};
		}

		final AsyncObjectLoaderQueue<T> lItr = reader.open(need, reportMissing);
		return new AsyncRevObjectQueue() {
			@Override
			public RevObject next() throws
					IOException {
				if(objItr.hasNext())
					return objItr.next();
				if(!lItr.next())
					return null;

				ObjectId id = lItr.getObjectId();
				ObjectLoader ldr = lItr.open();
				RevObject r = objects.get(id);
				if(r == null)
					r = parseNew(id, ldr);
				else if(r instanceof RevCommit) {
					byte[] raw = ldr.getCachedBytes();
					((RevCommit) r).parseCanonical(RevWalk.this, raw);
				} else if(r instanceof RevTag) {
					byte[] raw = ldr.getCachedBytes();
					((RevTag) r).parseCanonical(RevWalk.this, raw);
				} else
					r.flags |= PARSED;
				return r;
			}

			@Override
			public boolean cancel(boolean mayInterruptIfRunning) {
				return lItr.cancel(mayInterruptIfRunning);
			}

			@Override
			public void release() {
				lItr.release();
			}
		};
	}

	public void parseHeaders(RevObject obj)
			throws IOException {
		if((obj.flags & PARSED) == 0)
			obj.parseHeaders(this);
	}

	public void parseBody(RevObject obj)
			throws IOException {
		obj.parseBody(this);
	}

	public RevObject peel(RevObject obj) throws
			IOException {
		while(obj instanceof RevTag) {
			parseHeaders(obj);
			obj = ((RevTag) obj).getObject();
		}
		parseHeaders(obj);
		return obj;
	}

	public RevFlag newFlag(String name) {
		final int m = allocFlag();
		return new RevFlag(this, name, m);
	}

	int allocFlag() {
		if(freeFlags == 0)
			throw new IllegalArgumentException(MessageFormat.format(
					JGitText.get().flagsAlreadyCreated,
					32 - RESERVED_FLAGS));
		final int m = Integer.lowestOneBit(freeFlags);
		freeFlags &= ~m;
		return m;
	}

	public void carry(RevFlag flag) {
		if((freeFlags & flag.mask) != 0)
			throw new IllegalArgumentException(MessageFormat.format(JGitText.get().flagIsDisposed, flag.name));
		if(flag.walker != this)
			throw new IllegalArgumentException(MessageFormat.format(JGitText.get().flagNotFromThis, flag.name));
		carryFlags |= flag.mask;
	}

	void freeFlag(int mask) {
		retainOnReset &= ~mask;
		if(isNotStarted()) {
			freeFlags |= mask;
			carryFlags &= ~mask;
		} else {
			delayFreeFlags |= mask;
		}
	}

	private void finishDelayedFreeFlags() {
		if(delayFreeFlags != 0) {
			freeFlags |= delayFreeFlags;
			carryFlags &= ~delayFreeFlags;
			delayFreeFlags = 0;
		}
	}

	public final void reset() {
		reset(0);
	}

	public final void resetRetain(RevFlagSet retainFlags) {
		reset(retainFlags.mask);
	}

	public final void resetRetain(RevFlag... retainFlags) {
		int mask = 0;
		for(RevFlag flag : retainFlags)
			mask |= flag.mask;
		reset(mask);
	}

	protected void reset(int retainFlags) {
		finishDelayedFreeFlags();
		retainFlags |= PARSED | retainOnReset;
		final int clearFlags = ~retainFlags;

		final FIFORevQueue q = new FIFORevQueue();
		for(RevCommit c : roots) {
			if((c.flags & clearFlags) == 0)
				continue;
			c.flags &= retainFlags;
			c.reset();
			q.add(c);
		}

		for(; ; ) {
			final RevCommit c = q.next();
			if(c == null)
				break;
			if(c.parents == null)
				continue;
			for(RevCommit p : c.parents) {
				if((p.flags & clearFlags) == 0)
					continue;
				p.flags &= retainFlags;
				p.reset();
				q.add(p);
			}
		}

		roots.clear();
		queue = new DateRevQueue(firstParent);
		pending = new StartGenerator(this);
	}

	public void dispose() {
		reader.close();
		freeFlags = APP_FLAGS;
		delayFreeFlags = 0;
		retainOnReset = 0;
		carryFlags = UNINTERESTING;
		firstParent = false;
		objects.clear();
		roots.clear();
		queue = new DateRevQueue(firstParent);
		pending = new StartGenerator(this);
		shallowCommitsInitialized = false;
	}

	@Nullable
	private RevCommit nextForIterator() {
		try {
			return next();
		} catch(IOException e) {
			throw new RevWalkException(e);
		}
	}

	@Override
	public Iterator<RevCommit> iterator() {
		RevCommit first = nextForIterator();

		return new Iterator<RevCommit>() {
			RevCommit next = first;

			@Override
			public boolean hasNext() {
				return next != null;
			}

			@Override
			public RevCommit next() {
				RevCommit r = next;
				next = nextForIterator();
				return r;
			}

			@Override
			public void remove() {
				throw new UnsupportedOperationException();
			}
		};
	}

	protected void assertNotStarted() {
		if(isNotStarted())
			return;
		throw new IllegalStateException(JGitText.get().outputHasAlreadyBeenStarted);
	}

	protected void assertNoCommitsMarkedStart() {
		if(roots.isEmpty())
			return;
		throw new IllegalStateException(
				JGitText.get().commitsHaveAlreadyBeenMarkedAsStart);
	}

	private boolean isNotStarted() {
		return pending instanceof StartGenerator;
	}

	public ObjectWalk toObjectWalkWithSameObjects() {
		ObjectWalk ow = new ObjectWalk(reader);
		ow.objects = objects;
		ow.freeFlags = freeFlags;
		return ow;
	}

	protected RevCommit createCommit(AnyObjectId id) {
		return new RevCommit(id);
	}

	void carryFlagsImpl(RevCommit c) {
		final int carry = c.flags & carryFlags;
		if(carry != 0)
			RevCommit.carryFlags(c, carry);
	}

	public void assumeShallow(Collection<? extends ObjectId> ids) {
		for(ObjectId id : ids)
			lookupCommit(id).parents = RevCommit.NO_PARENTS;
	}

	void initializeShallowCommits(RevCommit rc) throws IOException {
		if(shallowCommitsInitialized) {
			throw new IllegalStateException(
					JGitText.get().shallowCommitsAlreadyInitialized);
		}

		shallowCommitsInitialized = true;

		if(reader == null) {
			return;
		}

		for(ObjectId id : reader.getShallowCommits()) {
			if(id.equals(rc.getId())) {
				rc.parents = RevCommit.NO_PARENTS;
			} else {
				lookupCommit(id).parents = RevCommit.NO_PARENTS;
			}
		}
	}
}
