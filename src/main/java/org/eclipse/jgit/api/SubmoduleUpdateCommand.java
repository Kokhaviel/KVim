/*
 * Copyright (C) 2011, GitHub Inc.
 * Copyright (C) 2016, Laurent Delaigue <laurent.delaigue@obeo.fr> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.api;

import org.eclipse.jgit.api.errors.*;
import org.eclipse.jgit.dircache.DirCacheCheckout;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.submodule.SubmoduleWalk;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class SubmoduleUpdateCommand extends TransportCommand<SubmoduleUpdateCommand, Collection<String>> {

	private ProgressMonitor monitor;
	private final Collection<String> paths;
	private final MergeStrategy strategy = MergeStrategy.RECURSIVE;
	private CloneCommand.Callback callback;

	public SubmoduleUpdateCommand(Repository repo) {
		super(repo);
		paths = new ArrayList<>();
	}

	public SubmoduleUpdateCommand setProgressMonitor(
			final ProgressMonitor monitor) {
		this.monitor = monitor;
		return this;
	}

	private Repository getOrCloneSubmodule(SubmoduleWalk generator, String url)
			throws IOException, GitAPIException {
		Repository repository = generator.getRepository();
		if (repository == null) {
			if (callback != null) {
				callback.cloningSubmodule(generator.getPath());
			}
			CloneCommand clone = Git.cloneRepository();
			configure(clone);
			clone.setURI(url);
			clone.setDirectory(generator.getDirectory());
			clone.setGitDir(
					new File(new File(repo.getDirectory(), Constants.MODULES),
							generator.getPath()));
			if (monitor != null) {
				clone.setProgressMonitor(monitor);
			}
			repository = clone.call().getRepository();
		}
		return repository;
	}

	@Override
	public Collection<String> call() throws
			GitAPIException {
		checkCallable();

		try (SubmoduleWalk generator = SubmoduleWalk.forIndex(repo)) {
			if (!paths.isEmpty())
				generator.setFilter(PathFilterGroup.createFromStrings(paths));
			List<String> updated = new ArrayList<>();
			while (generator.next()) {
				if (generator.getModulesPath() == null) continue;
				String url = generator.getConfigUrl();
				if (url == null) continue;

				try (Repository submoduleRepo = getOrCloneSubmodule(generator,
						url); RevWalk walk = new RevWalk(submoduleRepo)) {
					RevCommit commit = walk
							.parseCommit(generator.getObjectId());

					String update = generator.getConfigUpdate();
					if (ConfigConstants.CONFIG_KEY_MERGE.equals(update)) {
						MergeCommand merge = new MergeCommand(submoduleRepo);
						merge.include(commit);
						merge.setProgressMonitor(monitor);
						merge.setStrategy(strategy);
						merge.call();
					} else if (ConfigConstants.CONFIG_KEY_REBASE.equals(update)) {
						RebaseCommand rebase = new RebaseCommand(submoduleRepo);
						rebase.setUpstream(commit);
						rebase.setProgressMonitor(monitor);
						rebase.setStrategy(strategy);
						rebase.call();
					} else {
						DirCacheCheckout co = new DirCacheCheckout(
								submoduleRepo, submoduleRepo.lockDirCache(),
								commit.getTree());
						co.setFailOnConflict(true);
						co.setProgressMonitor(monitor);
						co.checkout();
						RefUpdate refUpdate = submoduleRepo.updateRef(
								Constants.HEAD, true);
						refUpdate.setNewObjectId(commit);
						refUpdate.forceUpdate();
						if (callback != null) {
							callback.checkingOut(commit,
									generator.getPath());
						}
					}
				}
				updated.add(generator.getPath());
			}
			return updated;
		} catch (IOException e) {
			throw new JGitInternalException(e.getMessage(), e);
		} catch (ConfigInvalidException e) {
			throw new InvalidConfigurationException(e.getMessage(), e);
		}
	}

	public SubmoduleUpdateCommand setCallback(CloneCommand.Callback callback) {
		this.callback = callback;
		return this;
	}

}
