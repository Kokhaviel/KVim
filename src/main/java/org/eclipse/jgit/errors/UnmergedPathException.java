/*
 * Copyright (C) 2008-2009, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.errors;

import java.io.IOException;
import java.text.MessageFormat;

import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.internal.JGitText;

public class UnmergedPathException extends IOException {
	private static final long serialVersionUID = 1L;

	public UnmergedPathException(DirCacheEntry dce) {
		super(MessageFormat.format(JGitText.get().unmergedPath, dce.getPathString()));
	}
}
