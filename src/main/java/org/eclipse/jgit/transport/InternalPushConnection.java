/*
 * Copyright (C) 2015, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.UncheckedIOException;

import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.resolver.ReceivePackFactory;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;

class InternalPushConnection<C> extends BasePackPushConnection {
	private Thread worker;

	public InternalPushConnection(PackTransport transport,
								  final ReceivePackFactory<C> receivePackFactory,
								  final C req, final Repository remote) throws TransportException {
		super(transport);

		final PipedInputStream in_r;
		final PipedOutputStream in_w;

		final PipedInputStream out_r;
		final PipedOutputStream out_w;
		try {
			in_r = new PipedInputStream();
			in_w = new PipedOutputStream(in_r);

			out_r = new PipedInputStream();
			out_w = new PipedOutputStream(out_r);
		} catch(IOException err) {
			remote.close();
			throw new TransportException(uri, JGitText.get().cannotConnectPipes, err);
		}

		worker = new Thread("JGit-Receive-Pack") {
			@Override
			public void run() {
				try {
					final ReceivePack rp = receivePackFactory.create(req, remote);
					rp.receive(out_r, in_w, System.err);
				} catch(ServiceNotEnabledException
						| ServiceNotAuthorizedException ignored) {
				} catch(IOException e) {
					throw new UncheckedIOException(e);
				} finally {
					try {
						out_r.close();
					} catch(IOException ignored) {
					}

					try {
						in_w.close();
					} catch(IOException ignored) {
					}

					remote.close();
				}
			}
		};
		worker.start();

		init(in_r, out_w);
		readAdvertisedRefs();
	}

	@Override
	public void close() {
		super.close();

		if(worker != null) {
			try {
				worker.join();
			} catch(InterruptedException ignored) {
			} finally {
				worker = null;
			}
		}
	}
}
