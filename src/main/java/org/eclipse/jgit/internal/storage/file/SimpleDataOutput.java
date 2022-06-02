/*
 * Copyright (C) 2012, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.file;

import java.io.DataOutput;
import java.io.IOException;
import java.io.OutputStream;

import org.eclipse.jgit.util.NB;

class SimpleDataOutput implements DataOutput {
	private final OutputStream fd;

	private final byte[] buf = new byte[8];

	SimpleDataOutput(OutputStream fd) {
		this.fd = fd;
	}

	@Override
	public void writeShort(int v) throws IOException {
		NB.encodeInt16(buf, 0, v);
		fd.write(buf, 0, 2);
	}

	@Override
	public void writeInt(int v) throws IOException {
		NB.encodeInt32(buf, 0, v);
		fd.write(buf, 0, 4);
	}

	@Override
	public void writeLong(long v) throws IOException {
		NB.encodeInt64(buf, 0, v);
		fd.write(buf, 0, 8);
	}

	@Override
	public void write(int b) throws IOException {
		throw new UnsupportedOperationException();
	}

	@Override
	public void write(byte[] b) throws IOException {
		throw new UnsupportedOperationException();
	}

	@Override
	public void write(byte[] b, int off, int len) throws IOException {
		throw new UnsupportedOperationException();
	}

	@Override
	public void writeBoolean(boolean v) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void writeByte(int v) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void writeChar(int v) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void writeFloat(float v) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void writeDouble(double v) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void writeBytes(String s) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void writeChars(String s) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void writeUTF(String s) {
		throw new UnsupportedOperationException();
	}
}
