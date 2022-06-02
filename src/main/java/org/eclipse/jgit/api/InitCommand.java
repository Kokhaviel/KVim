/*
 * Copyright (C) 2010, Chris Aniszczyk <caniszczyk@gmail.com> and others
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
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.StringUtils;
import org.eclipse.jgit.util.SystemReader;

import java.io.File;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.concurrent.Callable;

public class InitCommand implements Callable<Git> {

	private File directory;
	private File gitDir;
	private boolean bare;
	private FS fs;
	private String initialBranch;

	@Override
	public Git call() throws GitAPIException {
		try {
			RepositoryBuilder builder = new RepositoryBuilder();
			if(bare)
				builder.setBare();
			if(fs != null) {
				builder.setFS(fs);
			}
			builder.readEnvironment();
			if(gitDir != null)
				builder.setGitDir(gitDir);
			else
				gitDir = builder.getGitDir();
			if(directory != null) {
				if(bare)
					builder.setGitDir(directory);
				else {
					builder.setWorkTree(directory);
					if(gitDir == null)
						builder.setGitDir(new File(directory, Constants.DOT_GIT));
				}
			} else if(builder.getGitDir() == null) {
				String dStr = SystemReader.getInstance()
						.getProperty("user.dir");
				if(dStr == null)
					dStr = ".";
				File d = new File(dStr);
				if(!bare)
					d = new File(d, Constants.DOT_GIT);
				builder.setGitDir(d);
			} else {
				if(!bare) {
					String dStr = SystemReader.getInstance().getProperty(
							"user.dir");
					if(dStr == null)
						dStr = ".";
					builder.setWorkTree(new File(dStr));
				}
			}
			builder.setInitialBranch(StringUtils.isEmptyOrNull(initialBranch)
					? SystemReader.getInstance().getUserConfig().getString(
					ConfigConstants.CONFIG_INIT_SECTION, null,
					ConfigConstants.CONFIG_KEY_DEFAULT_BRANCH) : initialBranch);
			Repository repository = builder.build();
			if(!repository.getObjectDatabase().exists()) repository.create(bare);
			return new Git(repository, true);
		} catch(IOException | ConfigInvalidException e) {
			throw new JGitInternalException(e.getMessage(), e);
		}
	}

	public InitCommand setDirectory(File directory)
			throws IllegalStateException {
		validateDirs(directory, gitDir, bare);
		this.directory = directory;
		return this;
	}

	public InitCommand setGitDir(File gitDir)
			throws IllegalStateException {
		validateDirs(directory, gitDir, bare);
		this.gitDir = gitDir;
		return this;
	}

	private static void validateDirs(File directory, File gitDir, boolean bare)
			throws IllegalStateException {
		if(directory != null) {
			if(bare) {
				if(gitDir != null && !gitDir.equals(directory))
					throw new IllegalStateException(MessageFormat.format(
							JGitText.get().initFailedBareRepoDifferentDirs,
							gitDir, directory));
			} else {
				if(gitDir != null && gitDir.equals(directory))
					throw new IllegalStateException(MessageFormat.format(
							JGitText.get().initFailedNonBareRepoSameDirs,
							gitDir, directory));
			}
		}
	}

	public InitCommand setBare(boolean bare) {
		validateDirs(directory, gitDir, bare);
		this.bare = bare;
		return this;
	}

	public InitCommand setFs(FS fs) {
		this.fs = fs;
		return this;
	}

}
