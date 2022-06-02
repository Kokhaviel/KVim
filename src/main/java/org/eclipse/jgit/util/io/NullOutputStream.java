/*
 * Copyright (C) 2011, Stefan Lay <stefan.lay@.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.util.io;

import java.io.OutputStream;

public class NullOutputStream extends OutputStream {

	public static final NullOutputStream INSTANCE = new NullOutputStream();

	private NullOutputStream() {
	}

	@Override
	public void write(int b) {
	}

	@Override
	public void write(byte[] buf) {
	}

	@Override
	public void write(byte[] buf, int pos, int cnt) {
	}
}
