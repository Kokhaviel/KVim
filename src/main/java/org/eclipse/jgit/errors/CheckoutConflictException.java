/*
 * Copyright (C) 2007, Dave Watson <dwatson@mimvista.com>
 * Copyright (C) 2007, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2006, Shawn O. Pearce <spearce@spearce.org> and others
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

import org.eclipse.jgit.internal.JGitText;

public class CheckoutConflictException extends IOException {
	private static final long serialVersionUID = 1L;

	public CheckoutConflictException(String file) {
		super(MessageFormat.format(JGitText.get().checkoutConflictWithFile, file));
	}

	public CheckoutConflictException(String[] files) {
		super(MessageFormat.format(JGitText.get().checkoutConflictWithFiles, buildList(files)));
	}

	private static String buildList(String[] files) {
		StringBuilder builder = new StringBuilder();
		for (String f : files) {
			builder.append("\n");
			builder.append(f);
		}
		return builder.toString();
	}
}
