/*
 * Copyright (C) 2010, 2020 Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.diff;

import java.io.BufferedInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

import org.eclipse.jgit.errors.LargeObjectException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.ObjectStream;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.WorkingTreeIterator;
import org.eclipse.jgit.treewalk.filter.PathFilter;

public abstract class ContentSource {

	public static ContentSource create(ObjectReader reader) {
		return new ObjectReaderSource(reader);
	}

	public static ContentSource create(WorkingTreeIterator iterator) {
		return new WorkingTreeSource(iterator);
	}

	public abstract long size(String path, ObjectId id) throws IOException;

	public abstract ObjectLoader open(String path, ObjectId id)
			throws IOException;

	private static class ObjectReaderSource extends ContentSource {
		private final ObjectReader reader;

		ObjectReaderSource(ObjectReader reader) {
			this.reader = reader;
		}

		@Override
		public long size(String path, ObjectId id) throws IOException {
			try {
				return reader.getObjectSize(id, Constants.OBJ_BLOB);
			} catch(MissingObjectException ignore) {
				return 0;
			}
		}

		@Override
		public ObjectLoader open(String path, ObjectId id) throws IOException {
			return reader.open(id, Constants.OBJ_BLOB);
		}
	}

	private static class WorkingTreeSource extends ContentSource {
		private final TreeWalk tw;

		private final WorkingTreeIterator iterator;

		private String current;

		WorkingTreeIterator ptr;

		WorkingTreeSource(WorkingTreeIterator iterator) {
			this.tw = new TreeWalk(iterator.getRepository(), null);
			this.tw.setRecursive(true);
			this.iterator = iterator;
		}

		@Override
		public long size(String path, ObjectId id) throws IOException {
			seek(path);
			return ptr.getEntryLength();
		}

		@Override
		public ObjectLoader open(String path, ObjectId id) throws IOException {
			seek(path);
			long entrySize = ptr.getEntryContentLength();
			return new ObjectLoader() {
				@Override
				public long getSize() {
					return entrySize;
				}

				@Override
				public int getType() {
					return ptr.getEntryFileMode().getObjectType();
				}

				@Override
				public ObjectStream openStream() throws IOException {
					InputStream in = ptr.openEntryStream();
					in = new BufferedInputStream(in);
					return new ObjectStream.Filter(getType(), entrySize, in);
				}

				@Override
				public boolean isLarge() {
					return true;
				}

				@Override
				public byte[] getCachedBytes() throws LargeObjectException {
					throw new LargeObjectException();
				}
			};
		}

		private void seek(String path) throws IOException {
			if(!path.equals(current)) {
				iterator.reset();
				iterator.setWalkIgnoredDirectories(true);
				iterator.setDirCacheIterator(null, -1);
				tw.reset();
				tw.addTree(iterator);
				tw.setFilter(PathFilter.create(path));
				current = path;
				if(!tw.next())
					throw new FileNotFoundException(path);
				ptr = tw.getTree(0);
				if(ptr == null)
					throw new FileNotFoundException(path);
			}
		}
	}

	public static final class Pair {
		private final ContentSource oldSource;
		private final ContentSource newSource;

		public Pair(ContentSource oldSource, ContentSource newSource) {
			this.oldSource = oldSource;
			this.newSource = newSource;
		}

		public long size(DiffEntry.Side side, DiffEntry ent) throws IOException {
			switch(side) {
				case OLD:
					return oldSource.size(ent.oldPath, ent.oldId.toObjectId());
				case NEW:
					return newSource.size(ent.newPath, ent.newId.toObjectId());
				default:
					throw new IllegalArgumentException();
			}
		}

		public ObjectLoader open(DiffEntry.Side side, DiffEntry ent)
				throws IOException {
			switch(side) {
				case OLD:
					return oldSource.open(ent.oldPath, ent.oldId.toObjectId());
				case NEW:
					return newSource.open(ent.newPath, ent.newId.toObjectId());
				default:
					throw new IllegalArgumentException();
			}
		}
	}
}
