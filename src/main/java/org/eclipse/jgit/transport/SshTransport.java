/*
 * Copyright (C) 2009, Constantine Plotnikov <constantine.plotnikov@gmail.com>
 * Copyright (C) 2009, Google Inc.
 * Copyright (C) 2009, JetBrains s.r.o.
 * Copyright (C) 2008, Marek Zawirski <marek.zawirski@gmail.com>
 * Copyright (C) 2008-2009, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.util.FS;

public abstract class SshTransport extends TcpTransport {

	private SshSessionFactory sch;
	private RemoteSession sock;

	protected SshTransport(Repository local, URIish uri) {
		super(local, uri);
		sch = SshSessionFactory.getInstance();
	}

	protected SshTransport(URIish uri) {
		super(uri);
		sch = SshSessionFactory.getInstance();
	}

	public void setSshSessionFactory(SshSessionFactory factory) {
		if(factory == null)
			throw new NullPointerException(JGitText.get().theFactoryMustNotBeNull);
		if(sock != null)
			throw new IllegalStateException(
					JGitText.get().anSSHSessionHasBeenAlreadyCreated);
		sch = factory;
	}

	protected RemoteSession getSession() throws TransportException {
		if(sock != null)
			return sock;

		final int tms = getTimeout() > 0 ? getTimeout() * 1000 : 0;

		final FS fs = local == null ? FS.detect() : local.getFS();

		sock = sch
				.getSession(uri, getCredentialsProvider(), fs, tms);
		return sock;
	}

	@Override
	public void close() {
		if(sock != null) {
			try {
				sch.releaseSession(sock);
			} finally {
				sock = null;
			}
		}
	}
}
