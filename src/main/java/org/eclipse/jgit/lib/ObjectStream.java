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

import java.io.IOException;
import java.io.InputStream;

public abstract class ObjectStream extends InputStream {

	public abstract int getType();

	public abstract long getSize();

	public static class SmallStream extends ObjectStream {

		private final int type;
		private final byte[] data;
		private int ptr;
		private int mark;

		public SmallStream(ObjectLoader loader) {
			this(loader.getType(), loader.getCachedBytes());
		}

		public SmallStream(int type, byte[] data) {
			this.type = type;
			this.data = data;
		}

		@Override
		public int getType() {
			return type;
		}

		@Override
		public long getSize() {
			return data.length;
		}

		@Override
		public int available() {
			return data.length - ptr;
		}

		@Override
		public long skip(long n) {
			int s = (int) Math.min(available(), Math.max(0, n));
			ptr += s;
			return s;
		}

		@Override
		public int read() {
			if(ptr == data.length)
				return -1;
			return data[ptr++] & 0xff;
		}

		@Override
		public int read(byte[] b, int off, int len) {
			if(ptr == data.length)
				return -1;
			int n = Math.min(available(), len);
			System.arraycopy(data, ptr, b, off, n);
			ptr += n;
			return n;
		}

		@Override
		public boolean markSupported() {
			return true;
		}

		@Override
		public void mark(int readlimit) {
			mark = ptr;
		}

		@Override
		public void reset() {
			ptr = mark;
		}
	}

	public static class Filter extends ObjectStream {

		private final int type;
		private final long size;
		private final InputStream in;

		public Filter(int type, long size, InputStream in) {
			this.type = type;
			this.size = size;
			this.in = in;
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
		public int available() throws IOException {
			return in.available();
		}

		@Override
		public long skip(long n) throws IOException {
			return in.skip(n);
		}

		@Override
		public int read() throws IOException {
			return in.read();
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			return in.read(b, off, len);
		}

		@Override
		public boolean markSupported() {
			return in.markSupported();
		}

		@Override
		public void mark(int readlimit) {
			in.mark(readlimit);
		}

		@Override
		public void reset() throws IOException {
			in.reset();
		}

		@Override
		public void close() throws IOException {
			in.close();
		}
	}
}
