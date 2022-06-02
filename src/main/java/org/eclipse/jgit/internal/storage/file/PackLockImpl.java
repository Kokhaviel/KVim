/*
 * Copyright (C) 2009, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.file;

import java.io.File;
import java.io.IOException;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.transport.PackLock;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FileUtils;

public class PackLockImpl implements PackLock {
	private final File keepFile;

	public PackLockImpl(File packFile, FS fs) {
		final File p = packFile.getParentFile();
		final String n = packFile.getName();
		keepFile = new File(p, n.substring(0, n.length() - 5) + ".keep");
	}

	public boolean lock(String msg) throws IOException {
		if(msg == null)
			return false;
		if(!msg.endsWith("\n"))
			msg += "\n";
		final LockFile lf = new LockFile(keepFile);
		if(!lf.lock())
			return false;
		lf.write(Constants.encode(msg));
		return lf.commit();
	}

	@Override
	public void unlock() throws IOException {
		FileUtils.delete(keepFile);
	}
}
