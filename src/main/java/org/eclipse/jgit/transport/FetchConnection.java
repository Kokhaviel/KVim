/*
 * Copyright (C) 2008-2009, Google Inc.
 * Copyright (C) 2008, Marek Zawirski <marek.zawirski@gmail.com>
 * Copyright (C) 2008, Mike Ralphson <mike@abacus.co.uk>
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
import java.util.Collection;
import java.util.Set;

import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Ref;

public interface FetchConnection extends Connection {

	void fetch(final ProgressMonitor monitor,
			   final Collection<Ref> want, final Set<ObjectId> have)
			throws TransportException;


	void fetch(final ProgressMonitor monitor,
			   final Collection<Ref> want, final Set<ObjectId> have,
			   OutputStream out) throws TransportException;

	boolean didFetchIncludeTags();

	boolean didFetchTestConnectivity();

	void setPackLockMessage(String message);

	Collection<PackLock> getPackLocks();
}
