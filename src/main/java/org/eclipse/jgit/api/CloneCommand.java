/*
 * Copyright (C) 2011, 2017 Chris Aniszczyk <caniszczyk@gmail.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.api;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheCheckout;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.BranchConfig.BranchRebaseMode;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.submodule.SubmoduleWalk;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FileUtils;

public class CloneCommand extends TransportCommand<CloneCommand, Git> {

	private String uri;
	private File directory;
	private File gitDir;
	private boolean bare;
	private FS fs;
	private String remote = Constants.DEFAULT_REMOTE_NAME;
	private String branch = Constants.HEAD;
	private ProgressMonitor monitor = NullProgressMonitor.INSTANCE;
	private boolean cloneAllBranches;
	private boolean mirror;
	private boolean cloneSubmodules;
	private boolean noCheckout;
	private Collection<String> branchesToClone;
	private Callback callback;
	private boolean directoryExistsInitially;
	private boolean gitDirExistsInitially;
	private FETCH_TYPE fetchType;
	private TagOpt tagOption;

	private enum FETCH_TYPE {
		MULTIPLE_BRANCHES, ALL_BRANCHES, MIRROR
	}

	public interface Callback {
		void initializedSubmodules(Collection<String> submodules);

		void cloningSubmodule(String path);

		void checkingOut(AnyObjectId commit, String path);
	}

	public CloneCommand() {
		super(null);
	}

	@Override
	public Git call() throws GitAPIException {
		URIish u;
		try {
			u = new URIish(uri);
			verifyDirectories(u);
		} catch(URISyntaxException e) {
			throw new InvalidRemoteException(
					MessageFormat.format(JGitText.get().invalidURL, uri), e);
		}
		setFetchType();
		Repository repository = init();
		FetchResult fetchResult;
		Thread cleanupHook = new Thread(this::cleanup);
		try {
			Runtime.getRuntime().addShutdownHook(cleanupHook);
		} catch(IllegalStateException ignored) {
		}
		try {
			fetchResult = fetch(repository, u);
		} catch(IOException ioe) {
			repository.close();
			cleanup();
			throw new JGitInternalException(ioe.getMessage(), ioe);
		} catch(URISyntaxException e) {
			repository.close();
			cleanup();
			throw new InvalidRemoteException(
					MessageFormat.format(JGitText.get().invalidRemote, remote),
					e);
		} catch(GitAPIException | RuntimeException e) {
			if(repository != null) {
				repository.close();
			}
			cleanup();
			throw e;
		} finally {
			try {
				Runtime.getRuntime().removeShutdownHook(cleanupHook);
			} catch(IllegalStateException ignored) {
			}
		}
		if(!noCheckout) {
			try {
				checkout(repository, fetchResult);
			} catch(IOException ioe) {
				repository.close();
				throw new JGitInternalException(ioe.getMessage(), ioe);
			} catch(GitAPIException | RuntimeException e) {
				repository.close();
				throw e;
			}
		}
		return new Git(repository, true);
	}

	private void setFetchType() {
		if(mirror) {
			fetchType = FETCH_TYPE.MIRROR;
			setBare(true);
		} else if(cloneAllBranches) {
			fetchType = FETCH_TYPE.ALL_BRANCHES;
		} else if(branchesToClone != null && !branchesToClone.isEmpty()) {
			fetchType = FETCH_TYPE.MULTIPLE_BRANCHES;
		} else {
			fetchType = FETCH_TYPE.ALL_BRANCHES;
		}
	}

	private static boolean isNonEmptyDirectory(File dir) {
		if(dir != null && dir.exists()) {
			File[] files = dir.listFiles();
			return files != null && files.length != 0;
		}
		return false;
	}

	void verifyDirectories(URIish u) {
		if(directory == null && gitDir == null) {
			directory = new File(u.getHumanishName() + (bare ? Constants.DOT_GIT_EXT : ""));
		}
		directoryExistsInitially = directory != null && directory.exists();
		gitDirExistsInitially = gitDir != null && gitDir.exists();
		validateDirs(directory, gitDir, bare);
		if(isNonEmptyDirectory(directory)) {
			throw new JGitInternalException(MessageFormat.format(
					JGitText.get().cloneNonEmptyDirectory, directory.getName()));
		}
		if(isNonEmptyDirectory(gitDir)) {
			throw new JGitInternalException(MessageFormat.format(
					JGitText.get().cloneNonEmptyDirectory, gitDir.getName()));
		}
	}

	private Repository init() throws GitAPIException {
		InitCommand command = Git.init();
		command.setBare(bare);
		if(fs != null) {
			command.setFs(fs);
		}
		if(directory != null) {
			command.setDirectory(directory);
		}
		if(gitDir != null) {
			command.setGitDir(gitDir);
		}
		return command.call().getRepository();
	}

	private FetchResult fetch(Repository clonedRepo, URIish u) throws URISyntaxException, IOException, GitAPIException {
		RemoteConfig config = new RemoteConfig(clonedRepo.getConfig(), remote);
		config.addURI(u);

		boolean fetchAll = fetchType == FETCH_TYPE.ALL_BRANCHES
				|| fetchType == FETCH_TYPE.MIRROR;

		config.setFetchRefSpecs(calculateRefSpecs(fetchType, config.getName()));
		config.setMirror(fetchType == FETCH_TYPE.MIRROR);
		if(tagOption != null) {
			config.setTagOpt(tagOption);
		}
		config.update(clonedRepo.getConfig());

		clonedRepo.getConfig().save();

		// run the fetch command
		FetchCommand command = new FetchCommand(clonedRepo);
		command.setRemote(remote);
		command.setProgressMonitor(monitor);
		if(tagOption != null) {
			command.setTagOpt(tagOption);
		} else {
			command.setTagOpt(
					fetchAll ? TagOpt.FETCH_TAGS : TagOpt.AUTO_FOLLOW);
		}
		command.setInitialBranch(branch);
		configure(command);

		return command.call();
	}

	private List<RefSpec> calculateRefSpecs(FETCH_TYPE type,
											String remoteName) {
		List<RefSpec> specs = new ArrayList<>();
		if(type == FETCH_TYPE.MIRROR) {
			specs.add(new RefSpec().setForceUpdate(true).setSourceDestination(
					Constants.R_REFS + '*', Constants.R_REFS + '*'));
		} else {
			RefSpec heads = new RefSpec();
			heads = heads.setForceUpdate(true);
			final String dst = (bare ? Constants.R_HEADS
					: Constants.R_REMOTES + remoteName + '/') + '*';
			heads = heads.setSourceDestination(Constants.R_HEADS + '*', dst);
			if(type == FETCH_TYPE.MULTIPLE_BRANCHES) {
				RefSpec tags = new RefSpec().setForceUpdate(true)
						.setSourceDestination(Constants.R_TAGS + '*',
								Constants.R_TAGS + '*');
				for(String selectedRef : branchesToClone) {
					if(heads.matchSource(selectedRef)) {
						specs.add(heads.expandFromSource(selectedRef));
					} else if(tags.matchSource(selectedRef)) {
						specs.add(tags.expandFromSource(selectedRef));
					}
				}
			} else {
				specs.add(heads);
			}
		}
		return specs;
	}

	private void checkout(Repository clonedRepo, FetchResult result)
			throws
			IOException, GitAPIException {

		Ref head = null;
		if(branch.equals(Constants.HEAD)) {
			Ref foundBranch = findBranchToCheckout(result);
			if(foundBranch != null)
				head = foundBranch;
		}
		if(head == null) {
			head = result.getAdvertisedRef(branch);
			if(head == null)
				head = result.getAdvertisedRef(Constants.R_HEADS + branch);
			if(head == null)
				head = result.getAdvertisedRef(Constants.R_TAGS + branch);
		}

		if(head == null || head.getObjectId() == null)
			return;

		if(head.getName().startsWith(Constants.R_HEADS)) {
			final RefUpdate newHead = clonedRepo.updateRef(Constants.HEAD);
			newHead.disableRefLog();
			newHead.link(head.getName());
			addMergeConfig(clonedRepo, head);
		}

		final RevCommit commit = parseCommit(clonedRepo, head);

		boolean detached = !head.getName().startsWith(Constants.R_HEADS);
		RefUpdate u = clonedRepo.updateRef(Constants.HEAD, detached);
		u.setNewObjectId(commit.getId());
		u.forceUpdate();

		if(!bare) {
			DirCache dc = clonedRepo.lockDirCache();
			DirCacheCheckout co = new DirCacheCheckout(clonedRepo, dc,
					commit.getTree());
			co.setProgressMonitor(monitor);
			co.checkout();
			if(cloneSubmodules)
				cloneSubmodules(clonedRepo);
		}
	}

	private void cloneSubmodules(Repository clonedRepo) throws IOException,
			GitAPIException {
		SubmoduleInitCommand init = new SubmoduleInitCommand(clonedRepo);
		Collection<String> submodules = init.call();
		if(submodules.isEmpty()) {
			return;
		}
		if(callback != null) {
			callback.initializedSubmodules(submodules);
		}

		SubmoduleUpdateCommand update = new SubmoduleUpdateCommand(clonedRepo);
		configure(update);
		update.setProgressMonitor(monitor);
		update.setCallback(callback);
		if(!update.call().isEmpty()) {
			SubmoduleWalk walk = SubmoduleWalk.forIndex(clonedRepo);
			while(walk.next()) {
				try(Repository subRepo = walk.getRepository()) {
					if(subRepo != null) {
						cloneSubmodules(subRepo);
					}
				}
			}
		}
	}

	private Ref findBranchToCheckout(FetchResult result) {
		final Ref idHEAD = result.getAdvertisedRef(Constants.HEAD);
		ObjectId headId = idHEAD != null ? idHEAD.getObjectId() : null;
		if(headId == null) {
			return null;
		}

		if(idHEAD.isSymbolic()) {
			return idHEAD.getTarget();
		}

		Ref master = result.getAdvertisedRef(Constants.R_HEADS
				+ Constants.MASTER);
		ObjectId objectId = master != null ? master.getObjectId() : null;
		if(headId.equals(objectId)) {
			return master;
		}

		Ref foundBranch = null;
		for(Ref r : result.getAdvertisedRefs()) {
			final String n = r.getName();
			if(!n.startsWith(Constants.R_HEADS))
				continue;
			if(headId.equals(r.getObjectId())) {
				foundBranch = r;
				break;
			}
		}
		return foundBranch;
	}

	private void addMergeConfig(Repository clonedRepo, Ref head)
			throws IOException {
		String branchName = Repository.shortenRefName(head.getName());
		clonedRepo.getConfig().setString(ConfigConstants.CONFIG_BRANCH_SECTION,
				branchName, ConfigConstants.CONFIG_KEY_REMOTE, remote);
		clonedRepo.getConfig().setString(ConfigConstants.CONFIG_BRANCH_SECTION,
				branchName, ConfigConstants.CONFIG_KEY_MERGE, head.getName());
		String autosetupRebase = clonedRepo.getConfig().getString(
				ConfigConstants.CONFIG_BRANCH_SECTION, null,
				ConfigConstants.CONFIG_KEY_AUTOSETUPREBASE);
		if(ConfigConstants.CONFIG_KEY_ALWAYS.equals(autosetupRebase)
				|| ConfigConstants.CONFIG_KEY_REMOTE.equals(autosetupRebase))
			clonedRepo.getConfig().setEnum(
					ConfigConstants.CONFIG_BRANCH_SECTION, branchName,
					ConfigConstants.CONFIG_KEY_REBASE, BranchRebaseMode.REBASE);
		clonedRepo.getConfig().save();
	}

	private RevCommit parseCommit(Repository clonedRepo, Ref ref)
			throws
			IOException {
		final RevCommit commit;
		try(RevWalk rw = new RevWalk(clonedRepo)) {
			commit = rw.parseCommit(ref.getObjectId());
		}
		return commit;
	}

	public CloneCommand setURI(String uri) {
		this.uri = uri;
		return this;
	}

	public CloneCommand setDirectory(File directory) {
		validateDirs(directory, gitDir, bare);
		this.directory = directory;
		return this;
	}

	public CloneCommand setGitDir(File gitDir) {
		validateDirs(directory, gitDir, bare);
		this.gitDir = gitDir;
		return this;
	}

	public CloneCommand setBare(boolean bare) throws IllegalStateException {
		validateDirs(directory, gitDir, bare);
		this.bare = bare;
		return this;
	}

	public CloneCommand setFs(FS fs) {
		this.fs = fs;
		return this;
	}

	public CloneCommand setRemote(String remote) {
		if(remote == null) {
			remote = Constants.DEFAULT_REMOTE_NAME;
		}
		this.remote = remote;
		return this;
	}

	public CloneCommand setProgressMonitor(ProgressMonitor monitor) {
		if(monitor == null) {
			monitor = NullProgressMonitor.INSTANCE;
		}
		this.monitor = monitor;
		return this;
	}

	public CloneCommand setCloneAllBranches(boolean cloneAllBranches) {
		this.cloneAllBranches = cloneAllBranches;
		return this;
	}

	private static void validateDirs(File directory, File gitDir, boolean bare)
			throws IllegalStateException {
		if(directory != null) {
			if(directory.exists() && !directory.isDirectory()) {
				throw new IllegalStateException(MessageFormat.format(
						JGitText.get().initFailedDirIsNoDirectory, directory));
			}
			if(gitDir != null && gitDir.exists() && !gitDir.isDirectory()) {
				throw new IllegalStateException(MessageFormat.format(
						JGitText.get().initFailedGitDirIsNoDirectory, gitDir));
			}
			if(bare) {
				if(gitDir != null && !gitDir.equals(directory))
					throw new IllegalStateException(MessageFormat.format(
							JGitText.get().initFailedBareRepoDifferentDirs, gitDir, directory));
			} else {
				if(gitDir != null && gitDir.equals(directory))
					throw new IllegalStateException(MessageFormat.format(
							JGitText.get().initFailedNonBareRepoSameDirs, gitDir, directory));
			}
		}
	}

	private void cleanup() {
		try {
			if(directory != null) {
				if(!directoryExistsInitially) {
					FileUtils.delete(directory, FileUtils.RECURSIVE
							| FileUtils.SKIP_MISSING | FileUtils.IGNORE_ERRORS);
				} else {
					deleteChildren(directory);
				}
			}
			if(gitDir != null) {
				if(!gitDirExistsInitially) {
					FileUtils.delete(gitDir, FileUtils.RECURSIVE
							| FileUtils.SKIP_MISSING | FileUtils.IGNORE_ERRORS);
				} else {
					deleteChildren(gitDir);
				}
			}
		} catch(IOException ignored) {
		}
	}

	private void deleteChildren(File file) throws IOException {
		File[] files = file.listFiles();
		if(files == null) {
			return;
		}
		for(File child : files) {
			FileUtils.delete(child, FileUtils.RECURSIVE | FileUtils.SKIP_MISSING | FileUtils.IGNORE_ERRORS);
		}
	}
}
