/*
 * Copyright (C) 2015, Kaloyan Raev <kaloyan.r@zend.com> and others
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
import java.util.List;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;

public class RemoteSetUrlCommand extends GitCommand<RemoteConfig> {

	public enum UriType {
		FETCH,
		PUSH
	}


	private String remoteName;
	private URIish remoteUri;
	private UriType type;

	protected RemoteSetUrlCommand(Repository repo) {
		super(repo);
	}

	@Deprecated
	public void setName(String name) {
		this.remoteName = name;
	}

	public RemoteSetUrlCommand setRemoteName(String remoteName) {
		this.remoteName = remoteName;
		return this;
	}

	@Deprecated
	public void setUri(URIish uri) {
		this.remoteUri = uri;
	}

	@Deprecated
	public void setPush(boolean push) {
		if(push) {
			setUriType(UriType.PUSH);
		} else {
			setUriType(UriType.FETCH);
		}
	}

	public RemoteSetUrlCommand setUriType(UriType type) {
		this.type = type;
		return this;
	}

	@Override
	public RemoteConfig call() throws GitAPIException {
		checkCallable();

		try {
			StoredConfig config = repo.getConfig();
			RemoteConfig remote = new RemoteConfig(config, remoteName);
			if(type == UriType.PUSH) {
				List<URIish> uris = remote.getPushURIs();
				if(uris.size() > 1) {
					throw new JGitInternalException("remote.newtest.pushurl has multiple values");
				} else if(uris.size() == 1) {
					remote.removePushURI(uris.get(0));
				}
				remote.addPushURI(remoteUri);
			} else {
				List<URIish> uris = remote.getURIs();
				if(uris.size() > 1) {
					throw new JGitInternalException("remote.newtest.url has multiple values");
				} else if(uris.size() == 1) {
					remote.removeURI(uris.get(0));
				}
				remote.addURI(remoteUri);
			}

			remote.update(config);
			config.save();
			return remote;
		} catch(IOException | URISyntaxException e) {
			throw new JGitInternalException(e.getMessage(), e);
		}
	}

}
