/*
 * Copyright (C) 2011, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.util.io;

import java.io.IOException;
import java.io.OutputStream;

public class CountingOutputStream extends OutputStream {
	private final OutputStream out;
	private long cnt;

	public CountingOutputStream(OutputStream out) {
		this.out = out;
	}

	public long getCount() {
		return cnt;
	}

	@Override
	public void write(int val) throws IOException {
		out.write(val);
		cnt++;
	}

	@Override
	public void write(byte[] buf, int off, int len) throws IOException {
		out.write(buf, off, len);
		cnt += len;
	}

	@Override
	public void flush() throws IOException {
		out.flush();
	}

	@Override
	public void close() throws IOException {
		out.close();
	}
}
