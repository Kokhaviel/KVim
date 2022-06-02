/*
 * Copyright (C) 2007, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.errors;

import java.io.IOException;

public class NotSupportedException extends IOException {
	private static final long serialVersionUID = 1L;

	public NotSupportedException(String s) {
		super(s);
	}

	public NotSupportedException(String s, Throwable why) {
		super(s);
		initCause(why);
	}
}
