/*
 * Copyright (C) 2008, Marek Zawirski <marek.zawirski@gmail.com>
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.file;

import org.eclipse.jgit.errors.*;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.util.*;
import org.eclipse.jgit.util.io.SilentFileInputStream;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.MessageFormat;
import java.util.Iterator;
import java.util.Set;

public abstract class PackIndex implements Iterable<PackIndex.MutableEntry>, ObjectIdSet {

	public static PackIndex open(File idxFile) throws IOException {
		try(SilentFileInputStream fd = new SilentFileInputStream(
				idxFile)) {
			return read(fd);
		} catch(IOException ioe) {
			throw new IOException(
					MessageFormat.format(JGitText.get().unreadablePackIndex,
							idxFile.getAbsolutePath()),
					ioe);
		}
	}

	public static PackIndex read(InputStream fd) throws IOException {
		final byte[] hdr = new byte[8];
		IO.readFully(fd, hdr, 0, hdr.length);
		if(isTOC(hdr)) {
			final int v = NB.decodeInt32(hdr, 4);
			if(v == 2) {
				return new PackIndexV2(fd);
			}
			throw new UnsupportedPackIndexVersionException(v);
		}
		return new PackIndexV1(fd, hdr);
	}

	private static boolean isTOC(byte[] h) {
		final byte[] toc = PackIndexWriter.TOC;
		for(int i = 0; i < toc.length; i++)
			if(h[i] != toc[i])
				return false;
		return true;
	}

	protected byte[] packChecksum;

	public boolean hasObject(AnyObjectId id) {
		return findOffset(id) != -1;
	}

	@Override
	public boolean contains(AnyObjectId id) {
		return findOffset(id) != -1;
	}

	@Override
	public abstract Iterator<MutableEntry> iterator();

	public abstract long getObjectCount();

	public abstract ObjectId getObjectId(long nthPosition);

	public final ObjectId getObjectId(int nthPosition) {
		if(nthPosition >= 0)
			return getObjectId((long) nthPosition);
		final int u31 = nthPosition >>> 1;
		final int one = nthPosition & 1;
		return getObjectId(((long) u31) << 1 | one);
	}

	abstract long getOffset(long nthPosition);

	public abstract long findOffset(AnyObjectId objId);

	public abstract long findCRC32(AnyObjectId objId) throws MissingObjectException, UnsupportedOperationException;

	public abstract boolean hasCRC32Support();

	public abstract void resolve(Set<ObjectId> matches, AbbreviatedObjectId id, int matchLimit) throws IOException;

	public static class MutableEntry {
		final MutableObjectId idBuffer = new MutableObjectId();
		long offset;

		public long getOffset() {
			return offset;
		}

		public String name() {
			ensureId();
			return idBuffer.name();
		}

		public ObjectId toObjectId() {
			ensureId();
			return idBuffer.toObjectId();
		}

		void ensureId() {
		}
	}

	abstract class EntriesIterator implements Iterator<MutableEntry> {
		protected final MutableEntry entry = initEntry();

		protected long returnedNumber = 0;

		protected abstract MutableEntry initEntry();

		@Override
		public boolean hasNext() {
			return returnedNumber < getObjectCount();
		}

		@Override
		public abstract MutableEntry next();

		@Override
		public void remove() {
			throw new UnsupportedOperationException();
		}
	}
}
