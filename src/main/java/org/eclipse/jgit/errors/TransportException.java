/*
 * Copyright (C) 2008, Marek Zawirski <marek.zawirski@gmail.com>
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

import org.eclipse.jgit.transport.URIish;

public class TransportException extends IOException {
	private static final long serialVersionUID = 1L;

	public TransportException(URIish uri, String s) {
		super(uri.setPass(null) + ": " + s); 
	}

	public TransportException(final URIish uri, final String s,
			final Throwable cause) {
		this(uri.setPass(null) + ": " + s, cause); 
	}

	public TransportException(String s) {
		super(s);
	}

	public TransportException(String s, Throwable cause) {
		super(s);
		initCause(cause);
	}
}
