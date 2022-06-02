/*
 * Copyright (C) 2009, 2013 Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.util.io;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.LinkedList;

public class UnionInputStream extends InputStream {
	private static final InputStream EOF = new InputStream() {
		@Override
		public int read() {
			return -1;
		}
	};

	private final LinkedList<InputStream> streams = new LinkedList<>();

	public UnionInputStream(InputStream... inputStreams) {
		for(InputStream i : inputStreams)
			add(i);
	}

	private InputStream head() {
		return streams.isEmpty() ? EOF : streams.getFirst();
	}

	private void pop() throws IOException {
		if(!streams.isEmpty())
			streams.removeFirst().close();
	}

	public void add(InputStream in) {
		streams.add(in);
	}

	@Override
	public int read() throws IOException {
		for(; ; ) {
			final InputStream in = head();
			final int r = in.read();
			if(0 <= r)
				return r;
			else if(in == EOF)
				return -1;
			else
				pop();
		}
	}

	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		if(len == 0)
			return 0;
		for(; ; ) {
			final InputStream in = head();
			final int n = in.read(b, off, len);
			if(0 < n)
				return n;
			else if(in == EOF)
				return -1;
			else
				pop();
		}
	}

	@Override
	public int available() throws IOException {
		return head().available();
	}

	@Override
	public long skip(long count) throws IOException {
		long skipped = 0;
		long cnt = count;
		while(0 < cnt) {
			final InputStream in = head();
			final long n = in.skip(cnt);
			if(0 < n) {
				skipped += n;
				cnt -= n;

			} else if(in == EOF) {
				in.close();
				return skipped;

			} else {
				final int r = in.read();
				if(r < 0) {
					pop();
					if(0 < skipped)
						break;
				} else {
					skipped += 1;
					cnt -= 1;
				}
			}
		}
		return skipped;
	}

	@Override
	public void close() throws IOException {
		IOException err = null;

		for(Iterator<InputStream> i = streams.iterator(); i.hasNext(); ) {
			try {
				i.next().close();
			} catch(IOException closeError) {
				err = closeError;
			}
			i.remove();
		}

		if(err != null)
			throw err;
	}
}
