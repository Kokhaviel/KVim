/*
 * Copyright (C) 2009-2010, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.util.io;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;

import org.eclipse.jgit.util.RawParseUtils;

public class MessageWriter extends Writer {
	private final ByteArrayOutputStream buf;

	private final OutputStreamWriter enc;

	public MessageWriter() {
		buf = new ByteArrayOutputStream();
		enc = new OutputStreamWriter(getRawStream(), UTF_8);
	}

	@Override
	public void write(char[] cbuf, int off, int len) throws IOException {
		synchronized(buf) {
			enc.write(cbuf, off, len);
			enc.flush();
		}
	}

	public OutputStream getRawStream() {
		return buf;
	}

	@Override
	public void close() throws IOException {
	}

	@Override
	public void flush() throws IOException {
	}

	@Override
	public String toString() {
		return RawParseUtils.decode(buf.toByteArray());
	}
}
