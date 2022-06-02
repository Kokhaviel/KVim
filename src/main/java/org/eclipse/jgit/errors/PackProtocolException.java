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

import org.eclipse.jgit.transport.URIish;

public class PackProtocolException extends TransportException {
	private static final long serialVersionUID = 1L;

	public PackProtocolException(URIish uri, String s) {
		super(uri + ": " + s);
	}

	public PackProtocolException(String s) {
		super(s);
	}

	public PackProtocolException(String s, Throwable cause) {
		super(s);
		initCause(cause);
	}
}
