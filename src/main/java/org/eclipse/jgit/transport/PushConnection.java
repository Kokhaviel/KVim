/*
 * Copyright (C) 2008, Marek Zawirski <marek.zawirski@gmail.com>
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import java.io.OutputStream;
import java.util.Map;

import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.lib.ProgressMonitor;

public interface PushConnection extends Connection {

	void push(final ProgressMonitor monitor,
			  final Map<String, RemoteRefUpdate> refUpdates)
			throws TransportException;

	void push(final ProgressMonitor monitor,
			  final Map<String, RemoteRefUpdate> refUpdates, OutputStream out)
			throws TransportException;

}
