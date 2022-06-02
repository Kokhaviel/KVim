/*
 * Copyright (C) 2018, David Pursehouse <david.pursehouse@gmail.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.util.io;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

public class SilentFileInputStream extends FileInputStream {

	public SilentFileInputStream(File file) throws FileNotFoundException {
		super(file);
	}

	@Override
	public void close() {
		try {
			super.close();
		} catch(IOException ignored) {
		}
	}
}
