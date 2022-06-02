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

import java.net.URISyntaxException;
import java.util.List;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RemoteConfig;

public class RemoteListCommand extends GitCommand<List<RemoteConfig>> {

	protected RemoteListCommand(Repository repo) {
		super(repo);
	}

	@Override
	public List<RemoteConfig> call() throws GitAPIException {
		checkCallable();

		try {
			return RemoteConfig.getAllRemoteConfigs(repo.getConfig());
		} catch(URISyntaxException e) {
			throw new JGitInternalException(e.getMessage(), e);
		}
	}

}
