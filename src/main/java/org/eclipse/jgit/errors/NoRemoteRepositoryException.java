/*
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

public class NoRemoteRepositoryException extends TransportException {
	private static final long serialVersionUID = 1L;

	public NoRemoteRepositoryException(URIish uri, String s) {
		super(uri, s);
	}

	public NoRemoteRepositoryException(URIish uri, String s, Throwable cause) {
		super(uri, s, cause);
	}
}
