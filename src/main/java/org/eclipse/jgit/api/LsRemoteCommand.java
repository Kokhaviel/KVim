/*
 * Copyright (C) 2011, 2022 Christoph Brill <egore911@egore911.de> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.api;

import java.io.IOException;
import java.net.URISyntaxException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.errors.NotSupportedException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.FetchConnection;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UrlConfig;
import org.eclipse.jgit.util.SystemReader;

public class LsRemoteCommand extends TransportCommand<LsRemoteCommand, Collection<Ref>> {

	private String remote = Constants.DEFAULT_REMOTE_NAME;
	private boolean heads;
	private boolean tags;
	private String uploadPack;

	public LsRemoteCommand(Repository repo) {
		super(repo);
	}

	public LsRemoteCommand setRemote(String remote) {
		checkCallable();
		this.remote = remote;
		return this;
	}

	public LsRemoteCommand setTags(boolean tags) {
		this.tags = tags;
		return this;
	}

	@Override
	public Collection<Ref> call() throws GitAPIException {
		return execute().values();
	}

	public Map<String, Ref> callAsMap() throws GitAPIException {
		return Collections.unmodifiableMap(execute());
	}

	private Map<String, Ref> execute() throws GitAPIException {
		checkCallable();

		try(Transport transport = repo != null
				? Transport.open(repo, remote)
				: Transport.open(new URIish(translate(remote)))) {
			transport.setOptionUploadPack(uploadPack);
			configure(transport);
			Collection<RefSpec> refSpecs = new ArrayList<>(1);
			if(tags) refSpecs.add(new RefSpec("refs/tags/*:refs/remotes/origin/tags/*"));
			if(heads) refSpecs.add(new RefSpec("refs/heads/*:refs/remotes/origin/*"));
			Collection<Ref> refs;
			Map<String, Ref> refmap = new HashMap<>();
			try(FetchConnection fc = transport.openFetch(refSpecs)) {
				refs = fc.getRefs();
				if(refSpecs.isEmpty())
					for(Ref r : refs)
						refmap.put(r.getName(), r);
				else
					for(Ref r : refs)
						for(RefSpec rs : refSpecs)
							if(rs.matchSource(r)) {
								refmap.put(r.getName(), r);
								break;
							}
				return refmap;
			}
		} catch(URISyntaxException e) {
			throw new InvalidRemoteException(MessageFormat.format(
					JGitText.get().invalidRemote, remote), e);
		} catch(NotSupportedException e) {
			throw new JGitInternalException(
					JGitText.get().exceptionCaughtDuringExecutionOfLsRemoteCommand,
					e);
		} catch(IOException | ConfigInvalidException e) {
			throw new org.eclipse.jgit.api.errors.TransportException(
					e.getMessage(), e);
		}
	}

	private String translate(String uri)
			throws IOException, ConfigInvalidException {
		UrlConfig urls = new UrlConfig(SystemReader.getInstance().getUserConfig());
		return urls.replace(uri);
	}
}
