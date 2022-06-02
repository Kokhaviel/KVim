/*
 * Copyright (C) 2007, Dave Watson <dwatson@mimvista.com>
 * Copyright (C) 2008, 2010 Google Inc.
 * Copyright (C) 2008, Marek Zawirski <marek.zawirski@gmail.com>
 * Copyright (C) 2008, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2008, 2020 Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import org.eclipse.jgit.errors.NoRemoteRepositoryException;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.transport.resolver.ReceivePackFactory;
import org.eclipse.jgit.transport.resolver.UploadPackFactory;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.io.MessageWriter;
import org.eclipse.jgit.util.io.StreamCopyThread;

class TransportLocal extends Transport implements PackTransport {
	static final TransportProtocol PROTO_LOCAL = new TransportProtocol() {

		@Override
		public Set<String> getSchemes() {
			return Collections.singleton("file");
		}

		@Override
		public boolean canHandle(URIish uri, Repository local, String remoteName) {
			return uri.getPath() != null && uri.getPort() <= 0
					&& uri.getUser() == null && uri.getPass() == null && uri.getHost() == null
					&& (uri.getScheme() == null || getSchemes().contains(uri.getScheme()));
		}

		@Override
		public Transport open(URIish uri, Repository local, String remoteName)
				throws NoRemoteRepositoryException {
			File localPath = local.isBare() ? local.getDirectory() : local.getWorkTree();
			File path = local.getFS().resolve(localPath, uri.getPath());
			if(path.isFile())
				return new TransportBundleFile(local, uri, path);

			File gitDir = RepositoryCache.FileKey.resolve(path, local.getFS());
			if(gitDir == null)
				throw new NoRemoteRepositoryException(uri, JGitText.get().notFound);
			return new TransportLocal(local, uri, gitDir);
		}

		@Override
		public Transport open(URIish uri) throws
				TransportException {
			File path = FS.DETECTED.resolve(new File("."), uri.getPath());
			if(path.isFile())
				return new TransportBundleFile(uri, path);

			File gitDir = RepositoryCache.FileKey.resolve(path, FS.DETECTED);
			if(gitDir == null)
				throw new NoRemoteRepositoryException(uri,
						JGitText.get().notFound);
			return new TransportLocal(uri, gitDir);
		}
	};

	private final File remoteGitDir;

	TransportLocal(Repository local, URIish uri, File gitDir) {
		super(local, uri);
		remoteGitDir = gitDir;
	}

	TransportLocal(URIish uri, File gitDir) {
		super(uri);
		remoteGitDir = gitDir;
	}

	UploadPack createUploadPack(Repository dst) {
		return new UploadPack(dst);
	}

	ReceivePack createReceivePack(Repository dst) {
		return new ReceivePack(dst);
	}

	private Repository openRepo() throws TransportException {
		try {
			return new RepositoryBuilder()
					.setFS(local != null ? local.getFS() : FS.DETECTED)
					.setGitDir(remoteGitDir).build();
		} catch(IOException err) {
			throw new TransportException(uri,
					JGitText.get().notAGitDirectory, err);
		}
	}

	@Override
	public FetchConnection openFetch() throws TransportException {
		return openFetch(Collections.emptyList());
	}

	@Override
	public FetchConnection openFetch(Collection<RefSpec> refSpecs,
									 String... additionalPatterns) throws TransportException {
		final String up = getOptionUploadPack();
		if(!"git-upload-pack".equals(up)
				&& !"git upload-pack".equals(up)) {
			return new ForkLocalFetchConnection(refSpecs, additionalPatterns);
		}
		UploadPackFactory<Void> upf = (Void req,
									   Repository db) -> createUploadPack(db);
		return new InternalFetchConnection<>(this, upf, null, openRepo());
	}

	@Override
	public PushConnection openPush() throws TransportException {
		final String rp = getOptionReceivePack();
		if(!"git-receive-pack".equals(rp)
				&& !"git receive-pack".equals(rp))
			return new ForkLocalPushConnection();

		ReceivePackFactory<Void> rpf = (Void req,
										Repository db) -> createReceivePack(db);
		return new InternalPushConnection<>(this, rpf, null, openRepo());
	}

	@Override
	public void close() {
	}

	protected Process spawn(String cmd)
			throws TransportException {
		return spawn(cmd, null);
	}

	private Process spawn(String cmd,
						  TransferConfig.ProtocolVersion protocolVersion)
			throws TransportException {
		try {
			String[] args = {"."};
			ProcessBuilder proc = local.getFS().runInShell(cmd, args);
			proc.directory(remoteGitDir);

			Map<String, String> env = proc.environment();
			env.remove("GIT_ALTERNATE_OBJECT_DIRECTORIES");
			env.remove("GIT_CONFIG");
			env.remove("GIT_CONFIG_PARAMETERS");
			env.remove("GIT_DIR");
			env.remove("GIT_WORK_TREE");
			env.remove("GIT_GRAFT_FILE");
			env.remove("GIT_INDEX_FILE");
			env.remove("GIT_NO_REPLACE_OBJECTS");
			if(TransferConfig.ProtocolVersion.V2.equals(protocolVersion)) {
				env.put(GitProtocolConstants.PROTOCOL_ENVIRONMENT_VARIABLE,
						GitProtocolConstants.VERSION_2_REQUEST);
			}
			return proc.start();
		} catch(IOException err) {
			throw new TransportException(uri, err.getMessage(), err);
		}
	}

	class ForkLocalFetchConnection extends BasePackFetchConnection {
		private Process uploadPack;

		private Thread errorReaderThread;

		ForkLocalFetchConnection(Collection<RefSpec> refSpecs,
								 String... additionalPatterns) throws TransportException {
			super(TransportLocal.this);

			final MessageWriter msg = new MessageWriter();
			setMessageWriter(msg);

			TransferConfig.ProtocolVersion gitProtocol = protocol;
			if(gitProtocol == null) {
				gitProtocol = TransferConfig.ProtocolVersion.V2;
			}
			uploadPack = spawn(getOptionUploadPack(), gitProtocol);

			final InputStream upErr = uploadPack.getErrorStream();
			errorReaderThread = new StreamCopyThread(upErr, msg.getRawStream());
			errorReaderThread.start();

			InputStream upIn = uploadPack.getInputStream();
			OutputStream upOut = uploadPack.getOutputStream();

			upIn = new BufferedInputStream(upIn);
			upOut = new BufferedOutputStream(upOut);

			init(upIn, upOut);
			if(!readAdvertisedRefs()) {
				lsRefs(refSpecs, additionalPatterns);
			}
		}

		@Override
		public void close() {
			super.close();

			if(uploadPack != null) {
				try {
					uploadPack.waitFor();
				} catch(InterruptedException ignored) {
				} finally {
					uploadPack = null;
				}
			}

			if(errorReaderThread != null) {
				try {
					errorReaderThread.join();
				} catch(InterruptedException ignored) {
				} finally {
					errorReaderThread = null;
				}
			}
		}
	}

	class ForkLocalPushConnection extends BasePackPushConnection {
		private Process receivePack;

		private Thread errorReaderThread;

		ForkLocalPushConnection() throws TransportException {
			super(TransportLocal.this);

			final MessageWriter msg = new MessageWriter();
			setMessageWriter(msg);

			receivePack = spawn(getOptionReceivePack());

			final InputStream rpErr = receivePack.getErrorStream();
			errorReaderThread = new StreamCopyThread(rpErr, msg.getRawStream());
			errorReaderThread.start();

			InputStream rpIn = receivePack.getInputStream();
			OutputStream rpOut = receivePack.getOutputStream();

			rpIn = new BufferedInputStream(rpIn);
			rpOut = new BufferedOutputStream(rpOut);

			init(rpIn, rpOut);
			readAdvertisedRefs();
		}

		@Override
		public void close() {
			super.close();

			if(receivePack != null) {
				try {
					receivePack.waitFor();
				} catch(InterruptedException ignored) {
				} finally {
					receivePack = null;
				}
			}

			if(errorReaderThread != null) {
				try {
					errorReaderThread.join();
				} catch(InterruptedException ignored) {
				} finally {
					errorReaderThread = null;
				}
			}
		}
	}
}
