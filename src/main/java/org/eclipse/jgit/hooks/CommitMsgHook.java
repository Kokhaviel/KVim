/*
 * Copyright (C) 2015 Obeo. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.hooks;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;

import org.eclipse.jgit.api.errors.AbortedByHookException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;

public class CommitMsgHook extends GitHook<String> {

	public static final String NAME = "commit-msg";
	private String commitMessage;

	protected CommitMsgHook(Repository repo, PrintStream outputStream,
							PrintStream errorStream) {
		super(repo, outputStream, errorStream);
	}

	@Override
	public String call() throws IOException, AbortedByHookException {
		if(commitMessage == null) {
			throw new IllegalStateException();
		}
		if(canRun()) {
			getRepository().writeCommitEditMsg(commitMessage);
			doRun();
			commitMessage = getRepository().readCommitEditMsg();
		}
		return commitMessage;
	}

	private boolean canRun() {
		return getCommitEditMessageFilePath() != null && commitMessage != null;
	}

	@Override
	public String getHookName() {
		return NAME;
	}

	@Override
	protected String[] getParameters() {
		return new String[] {getCommitEditMessageFilePath()};
	}

	private String getCommitEditMessageFilePath() {
		File gitDir = getRepository().getDirectory();
		if(gitDir == null) {
			return null;
		}
		return Repository.stripWorkDir(getRepository().getWorkTree(), new File(
				gitDir, Constants.COMMIT_EDITMSG));
	}

	public CommitMsgHook setCommitMessage(String commitMessage) {
		this.commitMessage = commitMessage;
		return this;
	}

}
