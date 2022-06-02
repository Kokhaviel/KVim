/*
 * Copyright (C) 2008, Google Inc.
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
import java.util.Collection;
import java.util.Set;

import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Ref;

abstract class BaseFetchConnection extends BaseConnection implements
		FetchConnection {

	@Override
	public final void fetch(final ProgressMonitor monitor,
							final Collection<Ref> want, final Set<ObjectId> have)
			throws TransportException {
		fetch(monitor, want, have, null);
	}

	@Override
	public final void fetch(final ProgressMonitor monitor,
							final Collection<Ref> want, final Set<ObjectId> have,
							OutputStream out) throws TransportException {
		markStartedOperation();
		doFetch(monitor, want, have);
	}

	@Override
	public boolean didFetchIncludeTags() {
		return false;
	}

	protected abstract void doFetch(final ProgressMonitor monitor,
									final Collection<Ref> want, final Set<ObjectId> have)
			throws TransportException;
}
