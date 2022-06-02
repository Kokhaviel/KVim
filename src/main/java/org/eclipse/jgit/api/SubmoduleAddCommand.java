/*
 * Copyright (C) 2011, GitHub Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.api;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.api.errors.NoFilepatternException;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.internal.submodule.SubmoduleValidator;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.submodule.SubmoduleWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

import java.io.File;
import java.io.IOException;
import java.text.MessageFormat;

public class SubmoduleAddCommand extends TransportCommand<SubmoduleAddCommand, Repository> {

	private String name;
	private String path;
	private String uri;
	private ProgressMonitor monitor;

	public SubmoduleAddCommand(Repository repo) {
		super(repo);
	}

	public SubmoduleAddCommand setName(String name) {
		this.name = name;
		return this;
	}

	public SubmoduleAddCommand setPath(String path) {
		this.path = path;
		return this;
	}

	public SubmoduleAddCommand setURI(String uri) {
		this.uri = uri;
		return this;
	}

	public SubmoduleAddCommand setProgressMonitor(ProgressMonitor monitor) {
		this.monitor = monitor;
		return this;
	}

	protected boolean submoduleExists() throws IOException {
		TreeFilter filter = PathFilter.create(path);
		try(SubmoduleWalk w = SubmoduleWalk.forIndex(repo)) {
			return w.setFilter(filter).next();
		}
	}

	@Override
	public Repository call() throws GitAPIException {
		checkCallable();
		if(path == null || path.length() == 0)
			throw new IllegalArgumentException(JGitText.get().pathNotConfigured);
		if(uri == null || uri.length() == 0)
			throw new IllegalArgumentException(JGitText.get().uriNotConfigured);
		if(name == null || name.length() == 0) {
			name = path;
		}

		try {
			SubmoduleValidator.assertValidSubmoduleName(name);
			SubmoduleValidator.assertValidSubmodulePath(path);
			SubmoduleValidator.assertValidSubmoduleUri(uri);
		} catch(SubmoduleValidator.SubmoduleValidationException e) {
			throw new IllegalArgumentException(e.getMessage());
		}

		try {
			if(submoduleExists())
				throw new JGitInternalException(MessageFormat.format(
						JGitText.get().submoduleExists, path));
		} catch(IOException e) {
			throw new JGitInternalException(e.getMessage(), e);
		}

		final String resolvedUri;
		try {
			resolvedUri = SubmoduleWalk.getSubmoduleRemoteUrl(repo, uri);
		} catch(IOException e) {
			throw new JGitInternalException(e.getMessage(), e);
		}

		File moduleDirectory = SubmoduleWalk.getSubmoduleDirectory(repo, path);
		CloneCommand clone = Git.cloneRepository();
		configure(clone);
		clone.setDirectory(moduleDirectory);
		clone.setGitDir(new File(new File(repo.getDirectory(),
				Constants.MODULES), path));
		clone.setURI(resolvedUri);
		if(monitor != null)
			clone.setProgressMonitor(monitor);
		Repository subRepo;
		try(Git git = clone.call()) {
			subRepo = git.getRepository();
			subRepo.incrementOpen();
		}

		StoredConfig config = repo.getConfig();
		config.setString(ConfigConstants.CONFIG_SUBMODULE_SECTION, name,
				ConfigConstants.CONFIG_KEY_URL, resolvedUri);
		try {
			config.save();
		} catch(IOException e) {
			throw new JGitInternalException(e.getMessage(), e);
		}

		FileBasedConfig modulesConfig = new FileBasedConfig(new File(
				repo.getWorkTree(), Constants.DOT_GIT_MODULES), repo.getFS());
		try {
			modulesConfig.load();
			modulesConfig.setString(ConfigConstants.CONFIG_SUBMODULE_SECTION,
					name, ConfigConstants.CONFIG_KEY_PATH, path);
			modulesConfig.setString(ConfigConstants.CONFIG_SUBMODULE_SECTION,
					name, ConfigConstants.CONFIG_KEY_URL, uri);
			modulesConfig.save();
		} catch(IOException | ConfigInvalidException e) {
			throw new JGitInternalException(e.getMessage(), e);
		}

		AddCommand add = new AddCommand(repo);
		add.addFilepattern(Constants.DOT_GIT_MODULES);
		add.addFilepattern(path);
		try {
			add.call();
		} catch(NoFilepatternException e) {
			throw new JGitInternalException(e.getMessage(), e);
		}

		return subRepo;
	}
}
