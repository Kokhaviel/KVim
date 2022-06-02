/*
 * Copyright (C) 2008-2009, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.resolver.ReceivePackFactory;
import org.eclipse.jgit.transport.resolver.RepositoryResolver;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;
import org.eclipse.jgit.transport.resolver.UploadPackFactory;

public class Daemon {

	public static final int DEFAULT_PORT = 9418;
	private static final int BACKLOG = 5;
	private InetSocketAddress myAddress;
	private DaemonService[] services;
	private ThreadGroup processors;
	private Acceptor acceptThread;
	private int timeout;
	private RepositoryResolver<DaemonClient> repositoryResolver;
	volatile UploadPackFactory<DaemonClient> uploadPackFactory;
	volatile ReceivePackFactory<DaemonClient> receivePackFactory;

	public int getTimeout() {
		return timeout;
	}

	public void setTimeout(int seconds) {
		timeout = seconds;
	}

	private class Acceptor extends Thread {

		private final ServerSocket listenSocket;

		private final AtomicBoolean running = new AtomicBoolean(true);

		public Acceptor(ThreadGroup group, String name, ServerSocket socket) {
			super(group, name);
			this.listenSocket = socket;
		}

		@Override
		public void run() {
			setUncaughtExceptionHandler((thread, throwable) -> terminate());
			while(isRunning()) {
				try {
					startClient(listenSocket.accept());
				} catch(SocketException ignored) {
				} catch(IOException e) {
					break;
				}
			}

			terminate();
		}

		private void terminate() {
			try {
				shutDown();
			} finally {
				clearThread();
			}
		}

		public boolean isRunning() {
			return running.get();
		}

		public void shutDown() {
			running.set(false);
			try {
				listenSocket.close();
			} catch(IOException ignored) {
			}
		}

	}

	public synchronized void start() throws IOException {
		if(acceptThread != null) {
			throw new IllegalStateException(JGitText.get().daemonAlreadyRunning);
		}
		ServerSocket socket = new ServerSocket();
		socket.setReuseAddress(true);
		if(myAddress != null) {
			socket.bind(myAddress, BACKLOG);
		} else {
			socket.bind(new InetSocketAddress((InetAddress) null, 0), BACKLOG);
		}
		myAddress = (InetSocketAddress) socket.getLocalSocketAddress();

		acceptThread = new Acceptor(processors, "Git-Daemon-Accept", socket);
		acceptThread.start();
	}

	private synchronized void clearThread() {
		acceptThread = null;
	}

	void startClient(Socket s) {
		final DaemonClient dc = new DaemonClient(this);

		final SocketAddress peer = s.getRemoteSocketAddress();

		new Thread(processors, "Git-Daemon-Client " + peer.toString()) {
			@Override
			public void run() {
				try {
					dc.execute(s);
				} catch(ServiceNotEnabledException | ServiceNotAuthorizedException | IOException ignored) {
				} finally {
					try {
						s.getInputStream().close();
					} catch(IOException ignored) {
					}
					try {
						s.getOutputStream().close();
					} catch(IOException ignored) {
					}
				}
			}
		}.start();
	}

	synchronized DaemonService matchService(String cmd) {
		for(DaemonService d : services) {
			if(d.handles(cmd))
				return d;
		}
		return null;
	}

	Repository openRepository(DaemonClient client, String name)
			throws ServiceMayNotContinueException {
		name = name.replace('\\', '/');

		if(!name.startsWith("/"))
			return null;

		try {
			return repositoryResolver.open(client, name.substring(1));
		} catch(RepositoryNotFoundException | ServiceNotAuthorizedException | ServiceNotEnabledException e) {
			return null;
		}
	}
}
