/*
 * Copyright (C) 2021, Fabio Ponciroli <ponch@gerritforge.com>
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.errors;

import org.eclipse.jgit.internal.JGitText;

import java.io.IOException;
import java.text.MessageFormat;
import java.time.Duration;

public class SearchForReuseTimeout extends IOException {
	private static final long serialVersionUID = 1L;

	public SearchForReuseTimeout(Duration timeout) {
		super(MessageFormat.format(JGitText.get().searchForReuseTimeout, timeout.getSeconds()));
	}

	@Override
	public synchronized Throwable fillInStackTrace() {
		return this;
	}
}