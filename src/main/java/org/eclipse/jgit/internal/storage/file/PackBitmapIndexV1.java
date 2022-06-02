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

import java.io.*;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.util.*;

import com.googlecode.javaewah.EWAHCompressedBitmap;

class PackBitmapIndexV1 extends BasePackBitmapIndex {
	static final byte[] MAGIC = {'B', 'I', 'T', 'M'};
	static final int OPT_FULL = 1;

	private static final int MAX_XOR_OFFSET = 126;

	private static final ExecutorService executor = Executors.newCachedThreadPool(new ThreadFactory() {
				private final ThreadFactory baseFactory = Executors.defaultThreadFactory();
				private final AtomicInteger threadNumber = new AtomicInteger(0);

				@Override
				public Thread newThread(Runnable runnable) {
					Thread thread = baseFactory.newThread(runnable);
					thread.setName("JGit-PackBitmapIndexV1-"
							+ threadNumber.getAndIncrement());
					thread.setDaemon(true);
					return thread;
				}
			});

	private final PackIndex packIndex;
	private final PackReverseIndex reverseIndex;
	private final EWAHCompressedBitmap commits;
	private final EWAHCompressedBitmap trees;
	private final EWAHCompressedBitmap blobs;
	private final EWAHCompressedBitmap tags;

	private final ObjectIdOwnerMap<StoredBitmap> bitmaps;

	PackBitmapIndexV1(final InputStream fd, PackIndex packIndex,
					  PackReverseIndex reverseIndex) throws IOException {
		this(fd, () -> packIndex, () -> reverseIndex, false);
	}

	PackBitmapIndexV1(final InputStream fd, SupplierWithIOException<PackIndex> packIndexSupplier,
					  SupplierWithIOException<PackReverseIndex> reverseIndexSupplier, boolean loadParallelRevIndex) throws IOException {
		super(new ObjectIdOwnerMap<>());
		this.bitmaps = getBitmaps();

		Future<PackReverseIndex> reverseIndexFuture = null;
		if(loadParallelRevIndex) {
			reverseIndexFuture = executor.submit(reverseIndexSupplier::get);
		}

		final byte[] scratch = new byte[32];
		IO.readFully(fd, scratch, 0, scratch.length);

		for(int i = 0; i < MAGIC.length; i++) {
			if(scratch[i] != MAGIC[i]) {
				byte[] actual = new byte[MAGIC.length];
				System.arraycopy(scratch, 0, actual, 0, MAGIC.length);
				throw new IOException(MessageFormat.format(
						JGitText.get().expectedGot, Arrays.toString(MAGIC),
						Arrays.toString(actual)));
			}
		}

		final int version = NB.decodeUInt16(scratch, 4);
		if(version != 1)
			throw new IOException(MessageFormat.format(JGitText.get().unsupportedPackIndexVersion, version));

		final int opts = NB.decodeUInt16(scratch, 6);
		if((opts & OPT_FULL) == 0)
			throw new IOException(MessageFormat.format(JGitText.get().expectedGot, OPT_FULL, opts));

		long numEntries = NB.decodeUInt32(scratch, 8);
		if(numEntries > Integer.MAX_VALUE)
			throw new IOException(JGitText.get().indexFileIsTooLargeForJgit);

		this.packChecksum = new byte[20];
		System.arraycopy(scratch, 12, packChecksum, 0, packChecksum.length);

		SimpleDataInput dataInput = new SimpleDataInput(fd);
		this.commits = readBitmap(dataInput);
		this.trees = readBitmap(dataInput);
		this.blobs = readBitmap(dataInput);
		this.tags = readBitmap(dataInput);

		List<IdxPositionBitmap> idxPositionBitmapList = new ArrayList<>();
		IdxPositionBitmap[] recentBitmaps = new IdxPositionBitmap[MAX_XOR_OFFSET];
		for(int i = 0; i < (int) numEntries; i++) {
			IO.readFully(fd, scratch, 0, 6);
			int nthObjectId = NB.decodeInt32(scratch, 0);
			int xorOffset = scratch[4];
			int flags = scratch[5];
			EWAHCompressedBitmap bitmap = readBitmap(dataInput);

			if(nthObjectId < 0) {
				throw new IOException(MessageFormat.format(JGitText.get().invalidId, String.valueOf(nthObjectId)));
			}
			if(xorOffset < 0) {
				throw new IOException(MessageFormat.format(JGitText.get().invalidId, String.valueOf(xorOffset)));
			}
			if(xorOffset > MAX_XOR_OFFSET) {
				throw new IOException(MessageFormat.format(
						JGitText.get().expectedLessThanGot, String.valueOf(MAX_XOR_OFFSET), String.valueOf(xorOffset)));
			}
			if(xorOffset > i) {
				throw new IOException(MessageFormat.format(
						JGitText.get().expectedLessThanGot, String.valueOf(i), String.valueOf(xorOffset)));
			}
			IdxPositionBitmap xorIdxPositionBitmap = null;
			if(xorOffset > 0) {
				int index = (i - xorOffset);
				xorIdxPositionBitmap = recentBitmaps[index
						% recentBitmaps.length];
				if(xorIdxPositionBitmap == null) {
					throw new IOException(MessageFormat.format(JGitText.get().invalidId, String.valueOf(xorOffset)));
				}
			}
			IdxPositionBitmap idxPositionBitmap = new IdxPositionBitmap(
					nthObjectId, xorIdxPositionBitmap, bitmap, flags);
			idxPositionBitmapList.add(idxPositionBitmap);
			recentBitmaps[i % recentBitmaps.length] = idxPositionBitmap;
		}

		this.packIndex = packIndexSupplier.get();
		for(IdxPositionBitmap idxPositionBitmap : idxPositionBitmapList) {
			ObjectId objectId = packIndex.getObjectId(idxPositionBitmap.nthObjectId);
			StoredBitmap sb = new StoredBitmap(objectId, idxPositionBitmap.bitmap,
					idxPositionBitmap.getXorStoredBitmap(), idxPositionBitmap.flags);
			idxPositionBitmap.sb = sb;
			bitmaps.add(sb);
		}

		PackReverseIndex computedReverseIndex;
		if(loadParallelRevIndex) {
			try {
				computedReverseIndex = reverseIndexFuture.get();
			} catch(InterruptedException | ExecutionException e) {
				computedReverseIndex = reverseIndexSupplier.get();
			}
		} else {
			computedReverseIndex = reverseIndexSupplier.get();
		}
		this.reverseIndex = computedReverseIndex;
	}

	@Override
	public int findPosition(AnyObjectId objectId) {
		long offset = packIndex.findOffset(objectId);
		if(offset == -1)
			return -1;
		return reverseIndex.findPostion(offset);
	}

	@Override
	public ObjectId getObject(int position) throws IllegalArgumentException {
		ObjectId objectId = reverseIndex.findObjectByPosition(position);
		if(objectId == null)
			throw new IllegalArgumentException();
		return objectId;
	}

	@Override
	public int getObjectCount() {
		return (int) packIndex.getObjectCount();
	}

	@Override
	public EWAHCompressedBitmap ofObjectType(
			EWAHCompressedBitmap bitmap, int type) {
		switch(type) {
			case Constants.OBJ_BLOB:
				return blobs.and(bitmap);
			case Constants.OBJ_TREE:
				return trees.and(bitmap);
			case Constants.OBJ_COMMIT:
				return commits.and(bitmap);
			case Constants.OBJ_TAG:
				return tags.and(bitmap);
		}
		throw new IllegalArgumentException();
	}

	@Override
	public int getBitmapCount() {
		return bitmaps.size();
	}

	@Override
	public boolean equals(Object o) {
		if(o instanceof PackBitmapIndexV1)
			return getPackIndex() == ((PackBitmapIndexV1) o).getPackIndex();
		return false;
	}

	@Override
	public int hashCode() {
		return getPackIndex().hashCode();
	}

	PackIndex getPackIndex() {
		return packIndex;
	}

	private static EWAHCompressedBitmap readBitmap(DataInput dataInput) throws IOException {
		EWAHCompressedBitmap bitmap = new EWAHCompressedBitmap();
		bitmap.deserialize(dataInput);
		return bitmap;
	}

	private static final class IdxPositionBitmap {
		int nthObjectId;
		IdxPositionBitmap xorIdxPositionBitmap;
		EWAHCompressedBitmap bitmap;
		int flags;
		StoredBitmap sb;

		IdxPositionBitmap(int nthObjectId,
						  @Nullable IdxPositionBitmap xorIdxPositionBitmap,
						  EWAHCompressedBitmap bitmap, int flags) {
			this.nthObjectId = nthObjectId;
			this.xorIdxPositionBitmap = xorIdxPositionBitmap;
			this.bitmap = bitmap;
			this.flags = flags;
		}

		StoredBitmap getXorStoredBitmap() {
			return xorIdxPositionBitmap == null ? null : xorIdxPositionBitmap.sb;
		}
	}
}
