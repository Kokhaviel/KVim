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

import java.io.IOException;
import java.io.OutputStream;

import org.eclipse.jgit.internal.JGitText;

public final class DisabledOutputStream extends OutputStream {

	public static final DisabledOutputStream INSTANCE = new DisabledOutputStream();

	private DisabledOutputStream() {
	}

	@Override
	public void write(int b) throws IOException {
		throw new IllegalStateException(JGitText.get().writingNotPermitted);
	}
}
