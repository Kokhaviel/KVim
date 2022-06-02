/*
 * Copyright (C) 2012, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.revwalk;

import org.eclipse.jgit.errors.*;
import org.eclipse.jgit.internal.revwalk.*;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.lib.BitmapIndex.*;
import org.eclipse.jgit.revwalk.filter.ObjectFilter;

import java.io.IOException;
import java.util.Collections;

public final class BitmapWalker {

	private final ObjectWalk walker;
	private final BitmapIndex bitmapIndex;
	private final ProgressMonitor pm;
	private long countOfBitmapIndexMisses;
	private AnyObjectId prevCommit;
	private Bitmap prevBitmap;

	public BitmapWalker(
			ObjectWalk walker, BitmapIndex bitmapIndex, ProgressMonitor pm) {
		this.walker = walker;
		this.bitmapIndex = bitmapIndex;
		this.pm = (pm == null) ? NullProgressMonitor.INSTANCE : pm;
	}

	public void setPrevCommit(AnyObjectId prevCommit) {
		this.prevCommit = prevCommit;
	}

	public void setPrevBitmap(Bitmap prevBitmap) {
		this.prevBitmap = prevBitmap;
	}

	public long getCountOfBitmapIndexMisses() {
		return countOfBitmapIndexMisses;
	}

	public BitmapBuilder findObjects(Iterable<? extends ObjectId> start, BitmapBuilder seen, boolean ignoreMissing)
			throws IOException {
		if (!ignoreMissing) {
			return findObjectsWalk(start, seen, false);
		}

		try {
			return findObjectsWalk(start, seen, true);
		} catch (MissingObjectException ignore) {
		}

		final BitmapBuilder result = bitmapIndex.newBitmapBuilder();
		for (ObjectId obj : start) {
			Bitmap bitmap = bitmapIndex.getBitmap(obj);
			if (bitmap != null) {
				result.or(bitmap);
			}
		}

		for (ObjectId obj : start) {
			if (result.contains(obj)) {
				continue;
			}
			try {
				result.or(findObjectsWalk(Collections.singletonList(obj), result, false));
			} catch (MissingObjectException ignore) {
			}
		}
		return result;
	}

	private BitmapBuilder findObjectsWalk(Iterable<? extends ObjectId> start, BitmapBuilder seen,
			boolean ignoreMissingStart)
			throws IOException {
		walker.reset();
		final BitmapBuilder bitmapResult = bitmapIndex.newBitmapBuilder();

		for (ObjectId obj : start) {
			Bitmap bitmap = bitmapIndex.getBitmap(obj);
			if (bitmap != null)
				bitmapResult.or(bitmap);
		}

		boolean marked = false;
		for (ObjectId obj : start) {
			try {
				if (!bitmapResult.contains(obj)) {
					walker.markStart(walker.parseAny(obj));
					marked = true;
				}
			} catch (MissingObjectException e) {
				if (ignoreMissingStart)
					continue;
				throw e;
			}
		}

		if (marked) {
			if (prevCommit != null) {
				walker.setRevFilter(new AddToBitmapWithCacheFilter(prevCommit,
						prevBitmap, bitmapResult));
			} else if (seen == null) {
				walker.setRevFilter(new AddToBitmapFilter(bitmapResult));
			} else {
				walker.setRevFilter(
						new AddUnseenToBitmapFilter(seen, bitmapResult));
			}
			walker.setObjectFilter(new BitmapObjectFilter(bitmapResult));

			while (walker.next() != null) {
				pm.update(1);
				countOfBitmapIndexMisses++;
			}

			RevObject ro;
			while ((ro = walker.nextObject()) != null) {
				bitmapResult.addObject(ro, ro.getType());
				pm.update(1);
			}
		}

		return bitmapResult;
	}

	static class BitmapObjectFilter extends ObjectFilter {
		private final BitmapBuilder bitmap;

		BitmapObjectFilter(BitmapBuilder bitmap) {
			this.bitmap = bitmap;
		}

		@Override
		public final boolean include(ObjectWalk walker, AnyObjectId objid) throws IOException {
			return !bitmap.contains(objid);
		}
	}
}
