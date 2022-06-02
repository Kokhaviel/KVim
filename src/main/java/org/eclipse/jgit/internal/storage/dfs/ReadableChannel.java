/*
 * Copyright (C) 2011, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.dfs;

import java.io.IOException;
import java.nio.channels.ReadableByteChannel;

public interface ReadableChannel extends ReadableByteChannel {

	long position() throws IOException;

	void position(long newPosition) throws IOException;

	long size() throws IOException;

	int blockSize();

	void setReadAheadBytes(int bufferSize) throws IOException;
}
