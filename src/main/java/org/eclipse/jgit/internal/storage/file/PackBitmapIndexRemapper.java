/*
 * Copyright (C) 2013, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.file;

import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;

import org.eclipse.jgit.internal.storage.file.BasePackBitmapIndex.StoredBitmap;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.BitmapIndex;
import org.eclipse.jgit.lib.ObjectId;

import com.googlecode.javaewah.EWAHCompressedBitmap;
import com.googlecode.javaewah.IntIterator;

public class PackBitmapIndexRemapper extends PackBitmapIndex
		implements Iterable<PackBitmapIndexRemapper.Entry> {

	private final BasePackBitmapIndex oldPackIndex;
	final PackBitmapIndex newPackIndex;
	private final BitSet inflated;
	private final int[] prevToNewMapping;

	public static PackBitmapIndexRemapper newPackBitmapIndex(
			BitmapIndex prevBitmapIndex, PackBitmapIndex newIndex) {
		if(!(prevBitmapIndex instanceof BitmapIndexImpl))
			return new PackBitmapIndexRemapper(newIndex);

		PackBitmapIndex prevIndex = ((BitmapIndexImpl) prevBitmapIndex)
				.getPackBitmapIndex();
		if(!(prevIndex instanceof BasePackBitmapIndex))
			return new PackBitmapIndexRemapper(newIndex);

		return new PackBitmapIndexRemapper(
				(BasePackBitmapIndex) prevIndex, newIndex);
	}

	private PackBitmapIndexRemapper(PackBitmapIndex newPackIndex) {
		this.oldPackIndex = null;
		this.newPackIndex = newPackIndex;
		this.inflated = null;
		this.prevToNewMapping = null;
	}

	private PackBitmapIndexRemapper(
			BasePackBitmapIndex oldPackIndex, PackBitmapIndex newPackIndex) {
		this.oldPackIndex = oldPackIndex;
		this.newPackIndex = newPackIndex;
		inflated = new BitSet(newPackIndex.getObjectCount());

		prevToNewMapping = new int[oldPackIndex.getObjectCount()];
		for(int pos = 0; pos < prevToNewMapping.length; pos++)
			prevToNewMapping[pos] = newPackIndex.findPosition(
					oldPackIndex.getObject(pos));
	}

	@Override
	public int findPosition(AnyObjectId objectId) {
		return newPackIndex.findPosition(objectId);
	}

	@Override
	public ObjectId getObject(int position) throws IllegalArgumentException {
		return newPackIndex.getObject(position);
	}

	@Override
	public int getObjectCount() {
		return newPackIndex.getObjectCount();
	}

	@Override
	public EWAHCompressedBitmap ofObjectType(
			EWAHCompressedBitmap bitmap, int type) {
		return newPackIndex.ofObjectType(bitmap, type);
	}

	@Override
	public Iterator<Entry> iterator() {
		if(oldPackIndex == null)
			return Collections.emptyIterator();

		final Iterator<StoredBitmap> it = oldPackIndex.getBitmaps().iterator();
		return new Iterator<Entry>() {
			private Entry entry;

			@Override
			public boolean hasNext() {
				while(entry == null && it.hasNext()) {
					StoredBitmap sb = it.next();
					if(newPackIndex.findPosition(sb) != -1)
						entry = new Entry(sb, sb.getFlags());
				}
				return entry != null;
			}

			@Override
			public Entry next() {
				if(!hasNext())
					throw new NoSuchElementException();

				Entry res = entry;
				entry = null;
				return res;
			}

			@Override
			public void remove() {
				throw new UnsupportedOperationException();
			}
		};
	}

	@Override
	public EWAHCompressedBitmap getBitmap(AnyObjectId objectId) {
		EWAHCompressedBitmap bitmap = newPackIndex.getBitmap(objectId);
		if(bitmap != null || oldPackIndex == null)
			return bitmap;

		StoredBitmap oldBitmap = oldPackIndex.getBitmaps().get(objectId);
		if(oldBitmap == null)
			return null;

		if(newPackIndex.findPosition(objectId) == -1)
			return null;

		inflated.clear();
		for(IntIterator i = oldBitmap.getBitmapWithoutCaching()
				.intIterator(); i.hasNext(); )
			inflated.set(prevToNewMapping[i.next()]);
		bitmap = inflated.toEWAHCompressedBitmap();
		bitmap.trim();
		return bitmap;
	}

	public static final class Entry extends ObjectId {
		private final int flags;

		Entry(AnyObjectId src, int flags) {
			super(src);
			this.flags = flags;
		}

		public int getFlags() {
			return flags;
		}
	}

	@Override
	public int getBitmapCount() {
		return 0;
	}
}
