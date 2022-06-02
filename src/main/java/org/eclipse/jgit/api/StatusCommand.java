/*
 * Copyright (C) 2011, Christian Halstrick <christian.halstrick@sap.com> and others
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
import org.eclipse.jgit.errors.NoWorkTreeException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.IndexDiff;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.submodule.SubmoduleWalk.IgnoreSubmoduleMode;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.WorkingTreeIterator;

import java.io.IOException;

public class StatusCommand extends GitCommand<Status> {
	private WorkingTreeIterator workingTreeIt;
	private ProgressMonitor progressMonitor = null;
	private IgnoreSubmoduleMode ignoreSubmoduleMode = null;

	protected StatusCommand(Repository repo) {
		super(repo);
	}

	public StatusCommand setIgnoreSubmodules(IgnoreSubmoduleMode mode) {
		ignoreSubmoduleMode = mode;
		return this;
	}

	@Override
	public Status call() throws GitAPIException, NoWorkTreeException {
		if (workingTreeIt == null)
			workingTreeIt = new FileTreeIterator(repo);

		try {
			IndexDiff diff = new IndexDiff(repo, Constants.HEAD, workingTreeIt);
			if (ignoreSubmoduleMode != null)
				diff.setIgnoreSubmoduleMode(ignoreSubmoduleMode);
			if (progressMonitor == null)
				diff.diff();
			else
				diff.diff(progressMonitor, ProgressMonitor.UNKNOWN,
						ProgressMonitor.UNKNOWN, "");
			return new Status(diff);
		} catch (IOException e) {
			throw new JGitInternalException(e.getMessage(), e);
		}
	}
}
