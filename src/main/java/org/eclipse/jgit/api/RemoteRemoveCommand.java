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
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.RemoteConfig;

public class RemoteRemoveCommand extends GitCommand<RemoteConfig> {

	private String remoteName;

	protected RemoteRemoveCommand(Repository repo) {
		super(repo);
	}

	@Deprecated
	public void setName(String name) {
		this.remoteName = name;
	}

	public RemoteRemoveCommand setRemoteName(String remoteName) {
		this.remoteName = remoteName;
		return this;
	}

	@Override
	public RemoteConfig call() throws GitAPIException {
		checkCallable();

		try {
			StoredConfig config = repo.getConfig();
			RemoteConfig remote = new RemoteConfig(config, remoteName);
			config.unsetSection(ConfigConstants.CONFIG_KEY_REMOTE, remoteName);
			config.save();
			return remote;
		} catch(IOException | URISyntaxException e) {
			throw new JGitInternalException(e.getMessage(), e);
		}

	}

}
