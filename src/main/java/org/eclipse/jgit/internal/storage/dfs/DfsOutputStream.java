/*
 * Copyright (C) 2011, 2012 Google Inc. and others. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.dfs;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

public abstract class DfsOutputStream extends OutputStream {

	public int blockSize() {
		return 0;
	}

	@Override
	public void write(int b) throws IOException {
		write(new byte[] {(byte) b});
	}

	@Override
	public abstract void write(byte[] buf, int off, int len) throws IOException;

	public abstract int read(long position, ByteBuffer buf) throws IOException;
}
