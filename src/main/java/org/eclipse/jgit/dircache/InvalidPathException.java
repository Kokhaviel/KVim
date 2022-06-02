/*
 * Copyright (C) 2011, Robin Rosenberg and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.dircache;

import java.text.MessageFormat;

import org.eclipse.jgit.internal.JGitText;

public class InvalidPathException extends IllegalArgumentException {

	private static final long serialVersionUID = 1L;

	public InvalidPathException(String path) {
		this(JGitText.get().invalidPath, path);
	}

	InvalidPathException(String messagePattern, Object... arguments) {
		super(MessageFormat.format(messagePattern, arguments));
	}
}
