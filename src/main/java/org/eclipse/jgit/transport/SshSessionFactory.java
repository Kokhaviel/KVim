/*
 * Copyright (C) 2008, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2008, 2022 Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.util.FS;

import java.util.Iterator;
import java.util.ServiceLoader;

public abstract class SshSessionFactory {

	private static class DefaultFactory {

		private static volatile SshSessionFactory INSTANCE = loadSshSessionFactory();

		private static SshSessionFactory loadSshSessionFactory() {
			ServiceLoader<SshSessionFactory> loader = ServiceLoader
					.load(SshSessionFactory.class);
			Iterator<SshSessionFactory> iter = loader.iterator();
			if (iter.hasNext()) {
				return iter.next();
			}
			return null;
		}

		private DefaultFactory() {
		}

		public static SshSessionFactory getInstance() {
			return INSTANCE;
		}

		public static void setInstance(SshSessionFactory newFactory) {
			if (newFactory != null) {
				INSTANCE = newFactory;
			} else {
				INSTANCE = loadSshSessionFactory();
			}
		}
	}

	public static SshSessionFactory getInstance() {
		return DefaultFactory.getInstance();
	}

	public static void setInstance(SshSessionFactory newFactory) {
		DefaultFactory.setInstance(newFactory);
	}

	public abstract RemoteSession getSession(URIish uri,
			CredentialsProvider credentialsProvider, FS fs, int tms)
			throws TransportException;

	public abstract String getType();

	public void releaseSession(RemoteSession session) {
		session.disconnect();
	}
}
