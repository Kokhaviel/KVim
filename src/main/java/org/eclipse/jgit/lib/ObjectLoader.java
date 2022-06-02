/*
 * Copyright (C) 2008-2009, Google Inc.
 * Copyright (C) 2008, Jonas Fonseca <fonseca@diku.dk>
 * Copyright (C) 2008, Marek Zawirski <marek.zawirski@gmail.com>
 * Copyright (C) 2007, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2006-2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.lib;

import org.eclipse.jgit.errors.LargeObjectException;
import org.eclipse.jgit.util.IO;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;

public abstract class ObjectLoader {

	public abstract int getType();

	public abstract long getSize();

	public boolean isLarge() {
		try {
			getCachedBytes();
			return false;
		} catch (LargeObjectException tooBig) {
			return true;
		}
	}

	public final byte[] getBytes() throws LargeObjectException {
		return cloneArray(getCachedBytes());
	}

	public final byte[] getBytes(int sizeLimit) throws LargeObjectException, IOException {
		byte[] cached = getCachedBytes(sizeLimit);
		try {
			return cloneArray(cached);
		} catch (OutOfMemoryError tooBig) {
			throw new LargeObjectException.OutOfMemory(tooBig);
		}
	}

	public abstract byte[] getCachedBytes() throws LargeObjectException;

	public byte[] getCachedBytes(int sizeLimit) throws LargeObjectException, IOException {
		if (!isLarge())
			return getCachedBytes();

		try (ObjectStream in = openStream()) {
			long sz = in.getSize();
			if (sizeLimit < sz)
				throw new LargeObjectException.ExceedsLimit(sizeLimit, sz);

			byte[] buf;
			try {
				buf = new byte[(int) sz];
			} catch (OutOfMemoryError notEnoughHeap) {
				throw new LargeObjectException.OutOfMemory(notEnoughHeap);
			}

			IO.readFully(in, buf, 0, buf.length);
			return buf;
		}
	}

	public abstract ObjectStream openStream() throws IOException;

	public void copyTo(OutputStream out) throws IOException {
		if (isLarge()) {
			try (ObjectStream in = openStream()) {
				final long sz = in.getSize();
				byte[] tmp = new byte[8192];
				long copied = 0;
				while (copied < sz) {
					int n = in.read(tmp);
					if (n < 0)
						throw new EOFException();
					out.write(tmp, 0, n);
					copied += n;
				}
				if (0 <= in.read())
					throw new EOFException();
			}
		} else {
			out.write(getCachedBytes());
		}
	}

	private static byte[] cloneArray(byte[] data) {
		final byte[] copy = new byte[data.length];
		System.arraycopy(data, 0, copy, 0, data.length);
		return copy;
	}

	public static class SmallObject extends ObjectLoader {
		private final int type;

		private final byte[] data;

		public SmallObject(int type, byte[] data) {
			this.type = type;
			this.data = data;
		}

		@Override
		public int getType() {
			return type;
		}

		@Override
		public long getSize() {
			return getCachedBytes().length;
		}

		@Override
		public boolean isLarge() {
			return false;
		}

		@Override
		public byte[] getCachedBytes() {
			return data;
		}

		@Override
		public ObjectStream openStream() {
			return new ObjectStream.SmallStream(this);
		}
	}

	public abstract static class Filter extends ObjectLoader {

		protected abstract ObjectLoader delegate();

		@Override
		public int getType() {
			return delegate().getType();
		}

		@Override
		public long getSize() {
			return delegate().getSize();
		}

		@Override
		public boolean isLarge() {
			return delegate().isLarge();
		}

		@Override
		public byte[] getCachedBytes() {
			return delegate().getCachedBytes();
		}

		@Override
		public ObjectStream openStream() throws IOException {
			return delegate().openStream();
		}
	}
}
