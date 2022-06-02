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

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;

public class RemoteAddCommand extends GitCommand<RemoteConfig> {

	private String name;
	private URIish uri;

	protected RemoteAddCommand(Repository repo) {
		super(repo);
	}

	public RemoteAddCommand setName(String name) {
		this.name = name;
		return this;
	}

	public RemoteAddCommand setUri(URIish uri) {
		this.uri = uri;
		return this;
	}

	@Override
	public RemoteConfig call() throws GitAPIException {
		checkCallable();

		try {
			StoredConfig config = repo.getConfig();
			RemoteConfig remote = new RemoteConfig(config, name);

			RefSpec refSpec = new RefSpec();
			refSpec = refSpec.setForceUpdate(true);
			refSpec = refSpec.setSourceDestination(Constants.R_HEADS + "*", Constants.R_REMOTES + name + "/*");
			remote.addFetchRefSpec(refSpec);

			remote.addURI(uri);

			remote.update(config);
			config.save();
			return remote;
		} catch(IOException | URISyntaxException e) {
			throw new JGitInternalException(e.getMessage(), e);
		}

	}

}
