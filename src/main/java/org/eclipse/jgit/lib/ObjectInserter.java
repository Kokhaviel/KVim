/*
 * Copyright (C) 2007, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org>
 * Copyright (C) 2009, Google Inc.
 * Copyright (C) 2010, Chris Aniszczyk <caniszczyk@gmail.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.lib;

import java.io.*;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.transport.PackParser;
import org.eclipse.jgit.util.sha1.SHA1;

public abstract class ObjectInserter implements AutoCloseable {
	public static class Formatter extends ObjectInserter {
		@Override
		public ObjectId insert(int objectType, long length, InputStream in)
				throws IOException {
			throw new UnsupportedOperationException();
		}

		@Override
		public PackParser newPackParser(InputStream in) {
			throw new UnsupportedOperationException();
		}

		@Override
		public ObjectReader newReader() {
			throw new UnsupportedOperationException();
		}

		@Override
		public void flush() throws IOException {
		}

		@Override
		public void close() {
		}
	}

	public abstract static class Filter extends ObjectInserter {
		protected abstract ObjectInserter delegate();

		@Override
		protected byte[] buffer() {
			return delegate().buffer();
		}

		@Override
		public ObjectId idFor(int type, byte[] data) {
			return delegate().idFor(type, data);
		}

		@Override
		public ObjectId idFor(int type, byte[] data, int off, int len) {
			return delegate().idFor(type, data, off, len);
		}

		@Override
		public ObjectId idFor(int objectType, long length, InputStream in)
				throws IOException {
			return delegate().idFor(objectType, length, in);
		}

		@Override
		public ObjectId idFor(TreeFormatter formatter) {
			return delegate().idFor(formatter);
		}

		@Override
		public ObjectId insert(int type, byte[] data) throws IOException {
			return delegate().insert(type, data);
		}

		@Override
		public ObjectId insert(int type, byte[] data, int off, int len)
				throws IOException {
			return delegate().insert(type, data, off, len);
		}

		@Override
		public ObjectId insert(int objectType, long length, InputStream in)
				throws IOException {
			return delegate().insert(objectType, length, in);
		}

		@Override
		public PackParser newPackParser(InputStream in) throws IOException {
			return delegate().newPackParser(in);
		}

		@Override
		public ObjectReader newReader() {
			final ObjectReader dr = delegate().newReader();
			return new ObjectReader.Filter() {
				@Override
				protected ObjectReader delegate() {
					return dr;
				}

				@Override
				public ObjectInserter getCreatedFromInserter() {
					return ObjectInserter.Filter.this;
				}
			};
		}

		@Override
		public void flush() throws IOException {
			delegate().flush();
		}

		@Override
		public void close() {
			delegate().close();
		}
	}

	private final SHA1 hasher = SHA1.newInstance();

	private byte[] tempBuffer;

	protected ObjectInserter() {
	}

	protected byte[] buffer() {
		byte[] b = tempBuffer;
		if(b == null)
			tempBuffer = b = new byte[8192];
		return b;
	}

	protected SHA1 digest() {
		return hasher.reset();
	}

	public ObjectId idFor(int type, byte[] data) {
		return idFor(type, data, 0, data.length);
	}

	public ObjectId idFor(int type, byte[] data, int off, int len) {
		SHA1 md = SHA1.newInstance();
		md.update(Constants.encodedTypeString(type));
		md.update((byte) ' ');
		md.update(Constants.encodeASCII(len));
		md.update((byte) 0);
		md.update(data, off, len);
		return md.toObjectId();
	}

	public ObjectId idFor(int objectType, long length, InputStream in)
			throws IOException {
		SHA1 md = SHA1.newInstance();
		md.update(Constants.encodedTypeString(objectType));
		md.update((byte) ' ');
		md.update(Constants.encodeASCII(length));
		md.update((byte) 0);
		byte[] buf = buffer();
		while(length > 0) {
			int n = in.read(buf, 0, (int) Math.min(length, buf.length));
			if(n < 0)
				throw new EOFException(JGitText.get().unexpectedEndOfInput);
			md.update(buf, 0, n);
			length -= n;
		}
		return md.toObjectId();
	}

	public ObjectId idFor(TreeFormatter formatter) {
		return formatter.computeId(this);
	}

	public final ObjectId insert(TreeFormatter formatter) throws IOException {
		return formatter.insertTo(this);
	}

	public final ObjectId insert(CommitBuilder builder) throws IOException {
		return insert(Constants.OBJ_COMMIT, builder.build());
	}

	public final ObjectId insert(TagBuilder builder) throws IOException {
		return insert(Constants.OBJ_TAG, builder.build());
	}

	public ObjectId insert(int type, byte[] data)
			throws IOException {
		return insert(type, data, 0, data.length);
	}

	public ObjectId insert(int type, byte[] data, int off, int len)
			throws IOException {
		return insert(type, len, new ByteArrayInputStream(data, off, len));
	}

	public abstract ObjectId insert(int objectType, long length, InputStream in)
			throws IOException;

	public abstract PackParser newPackParser(InputStream in) throws IOException;

	public abstract ObjectReader newReader();

	public abstract void flush() throws IOException;

	@Override
	public abstract void close();
}
