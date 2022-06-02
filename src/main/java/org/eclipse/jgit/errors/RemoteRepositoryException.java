/*
 * Copyright (C) 2010, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.errors;

import org.eclipse.jgit.transport.URIish;

public class RemoteRepositoryException extends TransportException {
	private static final long serialVersionUID = 1L;

	public RemoteRepositoryException(URIish uri, String message) {
		super(uri, message);
	}
}
