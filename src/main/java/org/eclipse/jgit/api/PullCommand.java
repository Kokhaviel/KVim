/*
 * Copyright (C) 2010, Christian Halstrick <christian.halstrick@sap.com>
 * Copyright (C) 2010, Mathias Kinzler <mathias.kinzler@sap.com>
 * Copyright (C) 2016, 2021 Laurent Delaigue <laurent.delaigue@obeo.fr> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.api;

import org.eclipse.jgit.api.MergeCommand.FastForwardMode;
import org.eclipse.jgit.api.MergeCommand.FastForwardMode.Merge;
import org.eclipse.jgit.api.RebaseCommand.Operation;
import org.eclipse.jgit.api.errors.*;
import org.eclipse.jgit.dircache.DirCacheCheckout;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.lib.BranchConfig.BranchRebaseMode;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.lib.SubmoduleConfig.FetchRecurseSubmodulesMode;
import org.eclipse.jgit.merge.ContentMergeStrategy;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.TagOpt;

import java.io.IOException;
import java.text.MessageFormat;

public class PullCommand extends TransportCommand<PullCommand, PullResult> {

	private static final String DOT = ".";
	private final ProgressMonitor monitor = NullProgressMonitor.INSTANCE;
	private BranchRebaseMode pullRebaseMode = null;
	private String remote;
	private String remoteBranchName;
	private final MergeStrategy strategy = MergeStrategy.RECURSIVE;
	private ContentMergeStrategy contentStrategy;
	private TagOpt tagOption;
	private FastForwardMode fastForwardMode;
	private final FetchRecurseSubmodulesMode submoduleRecurseMode = null;

	protected PullCommand(Repository repo) {
		super(repo);
	}

	@Override
	public PullResult call() throws GitAPIException {
		checkCallable();

		monitor.beginTask(JGitText.get().pullTaskName, 2);
		Config repoConfig = repo.getConfig();

		String branchName = null;
		try {
			String fullBranch = repo.getFullBranch();
			if(fullBranch != null
					&& fullBranch.startsWith(Constants.R_HEADS)) {
				branchName = fullBranch.substring(Constants.R_HEADS.length());
			}
		} catch(IOException e) {
			throw new JGitInternalException(
					JGitText.get().exceptionCaughtDuringExecutionOfPullCommand,
					e);
		}
		if(remoteBranchName == null && branchName != null) {
			remoteBranchName = repoConfig.getString(
					ConfigConstants.CONFIG_BRANCH_SECTION, branchName, ConfigConstants.CONFIG_KEY_MERGE);
		}
		if(remoteBranchName == null) {
			remoteBranchName = branchName;
		}
		if(remoteBranchName == null) {
			throw new NoHeadException(JGitText.get().cannotCheckoutFromUnbornBranch);
		}

		if(!repo.getRepositoryState().equals(RepositoryState.SAFE))
			throw new WrongRepositoryStateException(MessageFormat.format(
					JGitText.get().cannotPullOnARepoWithState, repo.getRepositoryState().name()));

		if(remote == null && branchName != null) {
			remote = repoConfig.getString(
					ConfigConstants.CONFIG_BRANCH_SECTION, branchName,
					ConfigConstants.CONFIG_KEY_REMOTE);
		}
		if(remote == null) {
			remote = Constants.DEFAULT_REMOTE_NAME;
		}

		if(pullRebaseMode == null && branchName != null) {
			pullRebaseMode = getRebaseMode(branchName, repoConfig);
		}


		final boolean isRemote = !remote.equals(".");
		String remoteUri;
		FetchResult fetchRes;
		if(isRemote) {
			remoteUri = repoConfig.getString(
					ConfigConstants.CONFIG_REMOTE_SECTION, remote,
					ConfigConstants.CONFIG_KEY_URL);
			if(remoteUri == null) {
				String missingKey = ConfigConstants.CONFIG_REMOTE_SECTION + DOT
						+ remote + DOT + ConfigConstants.CONFIG_KEY_URL;
				throw new InvalidConfigurationException(MessageFormat.format(
						JGitText.get().missingConfigurationForKey, missingKey));
			}

			if(monitor.isCancelled())
				throw new CanceledException(MessageFormat.format(
						JGitText.get().operationCanceled, JGitText.get().pullTaskName));

			FetchCommand fetch = new FetchCommand(repo).setRemote(remote)
					.setProgressMonitor(monitor).setTagOpt(tagOption).setRecurseSubmodules(submoduleRecurseMode);
			configure(fetch);

			fetchRes = fetch.call();
		} else {
			remoteUri = JGitText.get().localRepository;
			fetchRes = null;
		}

		monitor.update(1);

		if(monitor.isCancelled())
			throw new CanceledException(MessageFormat.format(
					JGitText.get().operationCanceled, JGitText.get().pullTaskName));

		AnyObjectId commitToMerge;
		if(isRemote) {
			Ref r = null;
			if(fetchRes != null) {
				r = fetchRes.getAdvertisedRef(remoteBranchName);
				if(r == null) {
					r = fetchRes.getAdvertisedRef(Constants.R_HEADS
							+ remoteBranchName);
				}
			}
			if(r == null) {
				throw new RefNotAdvertisedException(MessageFormat.format(
						JGitText.get().couldNotGetAdvertisedRef, remote,
						remoteBranchName));
			}
			commitToMerge = r.getObjectId();
		} else {
			try {
				commitToMerge = repo.resolve(remoteBranchName);
				if(commitToMerge == null) {
					throw new RefNotFoundException(MessageFormat.format(
							JGitText.get().refNotResolved, remoteBranchName));
				}
			} catch(IOException e) {
				throw new JGitInternalException(
						JGitText.get().exceptionCaughtDuringExecutionOfPullCommand,
						e);
			}
		}

		String upstreamName = MessageFormat.format(
				JGitText.get().upstreamBranchName,
				Repository.shortenRefName(remoteBranchName), remoteUri);

		PullResult result;
		if(pullRebaseMode != BranchRebaseMode.NONE) {
			try {
				Ref head = repo.exactRef(Constants.HEAD);
				if(head == null) {
					throw new NoHeadException(JGitText
							.get().commitOnRepoWithoutHEADCurrentlyNotSupported);
				}
				ObjectId headId = head.getObjectId();
				if(headId == null) {
					try(RevWalk revWalk = new RevWalk(repo)) {
						RevCommit srcCommit = revWalk
								.parseCommit(commitToMerge);
						DirCacheCheckout dco = new DirCacheCheckout(repo,
								repo.lockDirCache(), srcCommit.getTree());
						dco.setFailOnConflict(true);
						dco.setProgressMonitor(monitor);
						dco.checkout();
						RefUpdate refUpdate = repo
								.updateRef(head.getTarget().getName());
						refUpdate.setNewObjectId(commitToMerge);
						refUpdate.setExpectedOldObjectId(null);
						refUpdate.setRefLogMessage("initial pull", false);
						if(refUpdate.update() != Result.NEW) {
							throw new NoHeadException(JGitText
									.get().commitOnRepoWithoutHEADCurrentlyNotSupported);
						}
						monitor.endTask();
						return new PullResult(fetchRes,
								RebaseResult.result(
										RebaseResult.Status.FAST_FORWARD
								));
					}
				}
			} catch(IOException e) {
				throw new JGitInternalException(JGitText
						.get().exceptionCaughtDuringExecutionOfPullCommand, e);
			}
			RebaseCommand rebase = new RebaseCommand(repo);
			RebaseResult rebaseRes = rebase.setUpstream(commitToMerge)
					.setProgressMonitor(monitor).setUpstreamName(upstreamName).setOperation(Operation.BEGIN)
					.setStrategy(strategy).setContentMergeStrategy(contentStrategy).setPreserveMerges(
							pullRebaseMode == BranchRebaseMode.PRESERVE).call();
			result = new PullResult(fetchRes, rebaseRes);
		} else {
			MergeCommand merge = new MergeCommand(repo);
			MergeResult mergeRes = merge.include(upstreamName, commitToMerge)
					.setProgressMonitor(monitor).setStrategy(strategy)
					.setContentMergeStrategy(contentStrategy).setFastForward(getFastForwardMode()).call();
			monitor.update(1);
			result = new PullResult(fetchRes, mergeRes);
		}
		monitor.endTask();
		return result;
	}

	public static BranchRebaseMode getRebaseMode(String branchName,
												 Config config) {
		BranchRebaseMode mode = config.getEnum(BranchRebaseMode.values(),
				ConfigConstants.CONFIG_BRANCH_SECTION,
				branchName, ConfigConstants.CONFIG_KEY_REBASE, null);
		if(mode == null) {
			mode = config.getEnum(BranchRebaseMode.values(),
					ConfigConstants.CONFIG_PULL_SECTION, null,
					ConfigConstants.CONFIG_KEY_REBASE, BranchRebaseMode.NONE);
		}
		return mode;
	}

	private FastForwardMode getFastForwardMode() {
		if(fastForwardMode != null) {
			return fastForwardMode;
		}
		Config config = repo.getConfig();
		Merge ffMode = config.getEnum(Merge.values(),
				ConfigConstants.CONFIG_PULL_SECTION, null,
				ConfigConstants.CONFIG_KEY_FF, null);
		return ffMode != null ? FastForwardMode.valueOf(ffMode) : null;
	}
}
