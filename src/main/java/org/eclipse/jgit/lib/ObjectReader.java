/*
 * Copyright (C) 2010, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.lib;

import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.internal.revwalk.BitmappedObjectReachabilityChecker;
import org.eclipse.jgit.internal.revwalk.BitmappedReachabilityChecker;
import org.eclipse.jgit.internal.revwalk.PedestrianObjectReachabilityChecker;
import org.eclipse.jgit.internal.revwalk.PedestrianReachabilityChecker;
import org.eclipse.jgit.revwalk.ObjectReachabilityChecker;
import org.eclipse.jgit.revwalk.ObjectWalk;
import org.eclipse.jgit.revwalk.ReachabilityChecker;
import org.eclipse.jgit.revwalk.RevWalk;

import java.io.IOException;
import java.util.*;

import static org.eclipse.jgit.lib.Constants.OBJECT_ID_ABBREV_STRING_LENGTH;

public abstract class ObjectReader implements AutoCloseable {

	public static final int OBJ_ANY = -1;

	protected int streamFileThreshold;

	public abstract ObjectReader newReader();

	public AbbreviatedObjectId abbreviate(AnyObjectId objectId)
			throws IOException {
		return abbreviate(objectId, OBJECT_ID_ABBREV_STRING_LENGTH);
	}

	public AbbreviatedObjectId abbreviate(AnyObjectId objectId, int len)
			throws IOException {
		if (len == Constants.OBJECT_ID_STRING_LENGTH)
			return AbbreviatedObjectId.fromObjectId(objectId);

		AbbreviatedObjectId abbrev = objectId.abbreviate(len);
		Collection<ObjectId> matches = resolve(abbrev);
		while (1 < matches.size() && len < Constants.OBJECT_ID_STRING_LENGTH) {
			abbrev = objectId.abbreviate(++len);
			List<ObjectId> n = new ArrayList<>(8);
			for (ObjectId candidate : matches) {
				if (abbrev.prefixCompare(candidate) == 0)
					n.add(candidate);
			}
			if (1 < n.size())
				matches = n;
			else
				matches = resolve(abbrev);
		}
		return abbrev;
	}

	public abstract Collection<ObjectId> resolve(AbbreviatedObjectId id)
			throws IOException;

	public boolean has(AnyObjectId objectId) throws IOException {
		return has(objectId, OBJ_ANY);
	}

	public boolean has(AnyObjectId objectId, int typeHint) throws IOException {
		try {
			open(objectId, typeHint);
			return true;
		} catch (MissingObjectException notFound) {
			return false;
		}
	}

	public ObjectLoader open(AnyObjectId objectId)
			throws IOException {
		return open(objectId, OBJ_ANY);
	}

	public abstract ObjectLoader open(AnyObjectId objectId, int typeHint)
			throws IOException;

	public abstract Set<ObjectId> getShallowCommits() throws IOException;

	public <T extends ObjectId> AsyncObjectLoaderQueue<T> open(
			Iterable<T> objectIds, final boolean reportMissing) {
		final Iterator<T> idItr = objectIds.iterator();
		return new AsyncObjectLoaderQueue<T>() {
			private T cur;

			@Override
			public boolean next() {
				if (idItr.hasNext()) {
					cur = idItr.next();
					return true;
				}
				return false;
			}

			@Override
			public T getCurrent() {
				return cur;
			}

			@Override
			public ObjectId getObjectId() {
				return cur;
			}

			@Override
			public ObjectLoader open() throws IOException {
				return ObjectReader.this.open(cur, OBJ_ANY);
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

	public long getObjectSize(AnyObjectId objectId, int typeHint)
			throws IOException {
		return open(objectId, typeHint).getSize();
	}

	public <T extends ObjectId> AsyncObjectSizeQueue<T> getObjectSize(
			Iterable<T> objectIds, final boolean reportMissing) {
		final Iterator<T> idItr = objectIds.iterator();
		return new AsyncObjectSizeQueue<T>() {
			private T cur;

			private long sz;

			@Override
			public boolean next() throws IOException {
				if (idItr.hasNext()) {
					cur = idItr.next();
					sz = getObjectSize(cur, OBJ_ANY);
					return true;
				}
				return false;
			}

			@Override
			public T getCurrent() {
				return cur;
			}

			@Override
			public ObjectId getObjectId() {
				return cur;
			}

			@Override
			public long getSize() {
				return sz;
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

	public void setAvoidUnreachableObjects(boolean avoid) {
	}

	public BitmapIndex getBitmapIndex() throws IOException {
		return null;
	}

	@NonNull
	public ReachabilityChecker createReachabilityChecker(RevWalk rw)
			throws IOException {
		if (getBitmapIndex() != null) {
			return new BitmappedReachabilityChecker(rw);
		}

		return new PedestrianReachabilityChecker(true, rw);
	}

	@NonNull
	public ObjectReachabilityChecker createObjectReachabilityChecker(
			ObjectWalk ow) throws IOException {
		if (getBitmapIndex() != null) {
			return new BitmappedObjectReachabilityChecker(ow);
		}

		return new PedestrianObjectReachabilityChecker(ow);
	}

	@Nullable
	public ObjectInserter getCreatedFromInserter() {
		return null;
	}

	@Override
	public abstract void close();

	public void setStreamFileThreshold(int threshold) {
		streamFileThreshold = threshold;
	}

	public int getStreamFileThreshold() {
		return streamFileThreshold;
	}

	public abstract static class Filter extends ObjectReader {

		protected abstract ObjectReader delegate();

		@Override
		public ObjectReader newReader() {
			return delegate().newReader();
		}

		@Override
		public AbbreviatedObjectId abbreviate(AnyObjectId objectId)
				throws IOException {
			return delegate().abbreviate(objectId);
		}

		@Override
		public AbbreviatedObjectId abbreviate(AnyObjectId objectId, int len)
				throws IOException {
			return delegate().abbreviate(objectId, len);
		}

		@Override
		public Collection<ObjectId> resolve(AbbreviatedObjectId id)
				throws IOException {
			return delegate().resolve(id);
		}

		@Override
		public boolean has(AnyObjectId objectId) throws IOException {
			return delegate().has(objectId);
		}

		@Override
		public boolean has(AnyObjectId objectId, int typeHint) throws IOException {
			return delegate().has(objectId, typeHint);
		}

		@Override
		public ObjectLoader open(AnyObjectId objectId)
				throws IOException {
			return delegate().open(objectId);
		}

		@Override
		public ObjectLoader open(AnyObjectId objectId, int typeHint)
				throws IOException {
			return delegate().open(objectId, typeHint);
		}

		@Override
		public Set<ObjectId> getShallowCommits() throws IOException {
			return delegate().getShallowCommits();
		}

		@Override
		public <T extends ObjectId> AsyncObjectLoaderQueue<T> open(
				Iterable<T> objectIds, boolean reportMissing) {
			return delegate().open(objectIds, reportMissing);
		}

		@Override
		public long getObjectSize(AnyObjectId objectId, int typeHint)
				throws IOException {
			return delegate().getObjectSize(objectId, typeHint);
		}

		@Override
		public <T extends ObjectId> AsyncObjectSizeQueue<T> getObjectSize(
				Iterable<T> objectIds, boolean reportMissing) {
			return delegate().getObjectSize(objectIds, reportMissing);
		}

		@Override
		public void setAvoidUnreachableObjects(boolean avoid) {
			delegate().setAvoidUnreachableObjects(avoid);
		}

		@Override
		public BitmapIndex getBitmapIndex() throws IOException {
			return delegate().getBitmapIndex();
		}

		@Override
		@Nullable
		public ObjectInserter getCreatedFromInserter() {
			return delegate().getCreatedFromInserter();
		}

		@Override
		public void close() {
			delegate().close();
		}
	}
}
