/*
 * Copyright (C) 2012, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.file;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.internal.storage.pack.BitmapCommit;
import org.eclipse.jgit.internal.storage.pack.ObjectToPack;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.BitmapIndex.Bitmap;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectIdOwnerMap;
import org.eclipse.jgit.util.BlockList;

import com.googlecode.javaewah.EWAHCompressedBitmap;

public class PackBitmapIndexBuilder extends BasePackBitmapIndex {
	private static final int MAX_XOR_OFFSET_SEARCH = 10;

	private final EWAHCompressedBitmap commits;
	private final EWAHCompressedBitmap trees;
	private final EWAHCompressedBitmap blobs;
	private final EWAHCompressedBitmap tags;
	private final BlockList<PositionEntry> byOffset;

	private final LinkedList<StoredBitmap>
			bitmapsToWriteXorBuffer = new LinkedList<>();

	private List<StoredEntry> bitmapsToWrite = new ArrayList<>();

	final ObjectIdOwnerMap<PositionEntry>
			positionEntries = new ObjectIdOwnerMap<>();

	public PackBitmapIndexBuilder(List<ObjectToPack> objects) {
		super(new ObjectIdOwnerMap<>());
		byOffset = new BlockList<>(objects.size());
		sortByOffsetAndIndex(byOffset, positionEntries, objects);

		int sizeInWords = Math.max(4, byOffset.size() / 64 / 3);
		commits = new EWAHCompressedBitmap(sizeInWords);
		trees = new EWAHCompressedBitmap(sizeInWords);
		blobs = new EWAHCompressedBitmap(sizeInWords);
		tags = new EWAHCompressedBitmap(sizeInWords);
		for(int i = 0; i < objects.size(); i++) {
			int type = objects.get(i).getType();
			switch(type) {
				case Constants.OBJ_COMMIT:
					commits.set(i);
					break;
				case Constants.OBJ_TREE:
					trees.set(i);
					break;
				case Constants.OBJ_BLOB:
					blobs.set(i);
					break;
				case Constants.OBJ_TAG:
					tags.set(i);
					break;
				default:
					throw new IllegalArgumentException(MessageFormat.format(
							JGitText.get().badObjectType, String.valueOf(type)));
			}
		}
		commits.trim();
		trees.trim();
		blobs.trim();
		tags.trim();
	}

	private static void sortByOffsetAndIndex(BlockList<PositionEntry> byOffset,
											 ObjectIdOwnerMap<PositionEntry> positionEntries,
											 List<ObjectToPack> entries) {
		for(int i = 0; i < entries.size(); i++) {
			positionEntries.add(new PositionEntry(entries.get(i), i));
		}
		entries.sort((ObjectToPack a, ObjectToPack b) -> Long.signum(a.getOffset() - b.getOffset()));
		for(int i = 0; i < entries.size(); i++) {
			PositionEntry e = positionEntries.get(entries.get(i));
			e.offsetPosition = i;
			byOffset.add(e);
		}
	}

	public void addBitmap(AnyObjectId objectId, Bitmap bitmap, int flags) {
		addBitmap(objectId, bitmap.retrieveCompressed(), flags);
	}

	public void processBitmapForWrite(BitmapCommit c, Bitmap bitmap,
									  int flags) {
		EWAHCompressedBitmap compressed = bitmap.retrieveCompressed();
		compressed.trim();
		StoredBitmap newest = new StoredBitmap(c, compressed, null, flags);

		bitmapsToWriteXorBuffer.add(newest);
		if(bitmapsToWriteXorBuffer.size() > MAX_XOR_OFFSET_SEARCH) {
			bitmapsToWrite.add(
					generateStoredEntry(bitmapsToWriteXorBuffer.pollFirst()));
		}

		if(c.isAddToIndex()) {
			addBitmap(c, bitmap, flags);
		}
	}

	private StoredEntry generateStoredEntry(StoredBitmap bitmapToWrite) {
		int bestXorOffset = 0;
		EWAHCompressedBitmap bestBitmap = bitmapToWrite.getBitmap();

		int offset = 1;
		for(StoredBitmap curr : bitmapsToWriteXorBuffer) {
			EWAHCompressedBitmap bitmap = curr.getBitmap()
					.xor(bitmapToWrite.getBitmap());
			if(bitmap.sizeInBytes() < bestBitmap.sizeInBytes()) {
				bestBitmap = bitmap;
				bestXorOffset = offset;
			}
			offset++;
		}

		PositionEntry entry = positionEntries.get(bitmapToWrite);
		if(entry == null) {
			throw new IllegalStateException();
		}
		bestBitmap.trim();

		return new StoredEntry(entry.namePosition, bestBitmap,
				bestXorOffset, bitmapToWrite.getFlags());
	}

	public void addBitmap(
			AnyObjectId objectId, EWAHCompressedBitmap bitmap, int flags) {
		bitmap.trim();
		StoredBitmap result = new StoredBitmap(objectId, bitmap, null, flags);
		getBitmaps().add(result);
	}

	@Override
	public EWAHCompressedBitmap ofObjectType(
			EWAHCompressedBitmap bitmap, int type) {
		switch(type) {
			case Constants.OBJ_BLOB:
				return getBlobs().and(bitmap);
			case Constants.OBJ_TREE:
				return getTrees().and(bitmap);
			case Constants.OBJ_COMMIT:
				return getCommits().and(bitmap);
			case Constants.OBJ_TAG:
				return getTags().and(bitmap);
		}
		throw new IllegalArgumentException();
	}

	@Override
	public int findPosition(AnyObjectId objectId) {
		PositionEntry entry = positionEntries.get(objectId);
		if(entry == null)
			return -1;
		return entry.offsetPosition;
	}

	@Override
	public ObjectId getObject(int position) throws IllegalArgumentException {
		ObjectId objectId = byOffset.get(position);
		if(objectId == null)
			throw new IllegalArgumentException();
		return objectId;
	}

	public EWAHCompressedBitmap getCommits() {
		return commits;
	}

	public EWAHCompressedBitmap getTrees() {
		return trees;
	}

	public EWAHCompressedBitmap getBlobs() {
		return blobs;
	}

	public EWAHCompressedBitmap getTags() {
		return tags;
	}

	public int getOptions() {
		return PackBitmapIndexV1.OPT_FULL;
	}

	@Override
	public int getBitmapCount() {
		return bitmapsToWriteXorBuffer.size() + bitmapsToWrite.size();
	}

	public void resetBitmaps(int size) {
		getBitmaps().clear();
		bitmapsToWrite = new ArrayList<>(size);
	}

	@Override
	public int getObjectCount() {
		return byOffset.size();
	}

	public List<StoredEntry> getCompressedBitmaps() {
		while(!bitmapsToWriteXorBuffer.isEmpty()) {
			bitmapsToWrite.add(
					generateStoredEntry(bitmapsToWriteXorBuffer.pollFirst()));
		}

		Collections.reverse(bitmapsToWrite);
		return bitmapsToWrite;
	}

	public static final class StoredEntry {
		private final long objectId;
		private final EWAHCompressedBitmap bitmap;
		private final int xorOffset;
		private final int flags;

		StoredEntry(long objectId, EWAHCompressedBitmap bitmap,
					int xorOffset, int flags) {
			this.objectId = objectId;
			this.bitmap = bitmap;
			this.xorOffset = xorOffset;
			this.flags = flags;
		}

		public EWAHCompressedBitmap getBitmap() {
			return bitmap;
		}

		public int getXorOffset() {
			return xorOffset;
		}

		public int getFlags() {
			return flags;
		}

		public long getObjectId() {
			return objectId;
		}
	}

	private static final class PositionEntry extends ObjectIdOwnerMap.Entry {
		final int namePosition;

		int offsetPosition;

		PositionEntry(AnyObjectId objectId, int namePosition) {
			super(objectId);
			this.namePosition = namePosition;
		}
	}
}
