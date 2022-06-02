/*
 * Copyright (C) 2010, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.dfs;

import org.eclipse.jgit.errors.LargeObjectException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectStream;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.InflaterInputStream;

final class LargePackedWholeObject extends ObjectLoader {
	private final int type;
	private final long size;
	private final long objectOffset;
	private final int headerLength;
	private final DfsPackFile pack;
	private final DfsObjDatabase db;

	LargePackedWholeObject(int type, long size, long objectOffset,
			int headerLength, DfsPackFile pack, DfsObjDatabase db) {
		this.type = type;
		this.size = size;
		this.objectOffset = objectOffset;
		this.headerLength = headerLength;
		this.pack = pack;
		this.db = db;
	}

	@Override
	public int getType() {
		return type;
	}

	@Override
	public long getSize() {
		return size;
	}

	@Override
	public boolean isLarge() {
		return true;
	}

	@Override
	public byte[] getCachedBytes() throws LargeObjectException {
		throw new LargeObjectException();
	}

	@Override
	public ObjectStream openStream() throws IOException {
		PackInputStream packIn;
		DfsReader ctx = db.newReader();
		try {
			try {
				packIn = new PackInputStream(
						pack, objectOffset + headerLength, ctx);
				ctx = null;
			} catch (IOException packGone) {
				ObjectId obj = pack.getReverseIdx(ctx).findObject(objectOffset);
				return ctx.open(obj, type).openStream();
			}
		} finally {
			if (ctx != null) {
				ctx.close();
			}
		}

		int bufsz = 8192;
		InputStream in = new BufferedInputStream(
				new InflaterInputStream(packIn, packIn.ctx.inflater(), bufsz),
				bufsz);
		return new ObjectStream.Filter(type, size, in);
	}
}
