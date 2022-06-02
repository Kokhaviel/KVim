/*
 * Copyright (C) 2009-2010, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.errors;

import java.io.File;
import java.text.MessageFormat;

import org.eclipse.jgit.internal.JGitText;

public class RepositoryNotFoundException extends TransportException {
	private static final long serialVersionUID = 1L;

	public RepositoryNotFoundException(File location) {
		this(location.getPath());
	}

	public RepositoryNotFoundException(String location) {
		super(message(location));
	}

	private static String message(String location) {
		return MessageFormat.format(JGitText.get().repositoryNotFound, location);
	}
}
