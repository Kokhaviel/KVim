/*
 * Copyright (C) 2010, Christian Halstrick <christian.halstrick@sap.com>
 * Copyright (C) 2010, 2021 Chris Aniszczyk <caniszczyk@gmail.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.api;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.util.FS;

import java.io.File;
import java.io.IOException;

import static java.util.Objects.requireNonNull;

public class Git implements AutoCloseable {

	private final Repository repo;
	private final boolean closeRepo;

	public static Git open(File dir) throws IOException {
		return open(dir, FS.DETECTED);
	}

	public static Git open(File dir, FS fs) throws IOException {
		RepositoryCache.FileKey key;

		key = RepositoryCache.FileKey.lenient(dir, fs);
		Repository db = new RepositoryBuilder().setFS(fs).setGitDir(key.getFile())
				.setMustExist(true).build();
		return new Git(db, true);
	}

	public static Git wrap(Repository repo) {
		return new Git(repo);
	}

	@Override
	public void close() {
		if(closeRepo)
			repo.close();
	}

	public static CloneCommand cloneRepository() {
		return new CloneCommand();
	}

	public static LsRemoteCommand lsRemoteRepository() {
		return new LsRemoteCommand(null);
	}

	public static InitCommand init() {
		return new InitCommand();
	}

	public Git(Repository repo) {
		this(repo, false);
	}

	Git(Repository repo, boolean closeRepo) {
		this.repo = requireNonNull(repo);
		this.closeRepo = closeRepo;
	}

	public CommitCommand commit() {
		return new CommitCommand(repo);
	}

	public LogCommand log() {
		return new LogCommand(repo);
	}

	public MergeCommand merge() {
		return new MergeCommand(repo);
	}

	public PullCommand pull() {
		return new PullCommand(repo);
	}

	public CreateBranchCommand branchCreate() {
		return new CreateBranchCommand(repo);
	}

	public DeleteBranchCommand branchDelete() {
		return new DeleteBranchCommand(repo);
	}

	public ListBranchCommand branchList() {
		return new ListBranchCommand(repo);
	}

	public AddCommand add() {
		return new AddCommand(repo);
	}

	public TagCommand tag() {
		return new TagCommand(repo);
	}

	public FetchCommand fetch() {
		return new FetchCommand(repo);
	}

	public PushCommand push() {
		return new PushCommand(repo);
	}

	public CherryPickCommand cherryPick() {
		return new CherryPickCommand(repo);
	}

	public RmCommand rm() {
		return new RmCommand(repo);
	}

	public CheckoutCommand checkout() {
		return new CheckoutCommand(repo);
	}

	public ResetCommand reset() {
		return new ResetCommand(repo);
	}

	public StatusCommand status() {
		return new StatusCommand(repo);
	}

	public DiffCommand diff() {
		return new DiffCommand(repo);
	}

	public SubmoduleAddCommand submoduleAdd() {
		return new SubmoduleAddCommand(repo);
	}

	public StashCreateCommand stashCreate() {
		return new StashCreateCommand(repo);
	}

	public StashApplyCommand stashApply() {
		return new StashApplyCommand(repo);
	}

	public RemoteListCommand remoteList() {
		return new RemoteListCommand(repo);
	}

	public RemoteAddCommand remoteAdd() {
		return new RemoteAddCommand(repo);
	}

	public Repository getRepository() {
		return repo;
	}

	@Override
	public String toString() {
		return "Git[" + repo + "]";
	}
}
