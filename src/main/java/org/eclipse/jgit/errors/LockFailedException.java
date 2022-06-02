/*
 * Copyright (C) 2011, GitHub Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.errors;

import java.io.File;
import java.io.IOException;
import java.text.MessageFormat;

import org.eclipse.jgit.internal.JGitText;

public class LockFailedException extends IOException {
	private static final long serialVersionUID = 1L;

	public LockFailedException(String message) {
		super(message);
	}

	public LockFailedException(File file) {
		this(MessageFormat.format(JGitText.get().cannotLock, file));
	}
}
