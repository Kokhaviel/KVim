/*
 * Copyright (C) 2010, Mathias Kinzler <mathias.kinzler@sap.com>
 * Copyright (C) 2010, Chris Aniszczyk <caniszczyk@gmail.com>
 * Copyright (C) 2014, Robin Stocker <robin@nibor.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.api;

import static org.eclipse.jgit.lib.Constants.HEAD;
import static org.eclipse.jgit.lib.Constants.R_HEADS;
import static org.eclipse.jgit.lib.Constants.R_REMOTES;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.*;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.RevWalkUtils;

public class ListBranchCommand extends GitCommand<List<Ref>> {
	private ListMode listMode;
	private String containsCommitish;

	public enum ListMode {
		ALL,
		REMOTE
	}

	protected ListBranchCommand(Repository repo) {
		super(repo);
	}

	@Override
	public List<Ref> call() throws GitAPIException {
		checkCallable();
		List<Ref> resultRefs;
		try {
			Collection<Ref> refs = new ArrayList<>();

			Ref head = repo.exactRef(HEAD);
			if(head != null && head.getLeaf().getName().equals(HEAD)) {
				refs.add(head);
			}

			if(listMode == null) {
				refs.addAll(repo.getRefDatabase().getRefsByPrefix(R_HEADS));
			} else if(listMode == ListMode.REMOTE) {
				refs.addAll(repo.getRefDatabase().getRefsByPrefix(R_REMOTES));
			} else {
				refs.addAll(repo.getRefDatabase().getRefsByPrefix(R_HEADS,
						R_REMOTES));
			}
			resultRefs = new ArrayList<>(filterRefs(refs));
		} catch(IOException e) {
			throw new JGitInternalException(e.getMessage(), e);
		}

		resultRefs.sort(Comparator.comparing(Ref::getName));
		setCallable(false);
		return resultRefs;
	}

	private Collection<Ref> filterRefs(Collection<Ref> refs)
			throws RefNotFoundException, IOException {
		if(containsCommitish == null)
			return refs;

		try(RevWalk walk = new RevWalk(repo)) {
			ObjectId resolved = repo.resolve(containsCommitish);
			if(resolved == null)
				throw new RefNotFoundException(MessageFormat.format(
						JGitText.get().refNotResolved, containsCommitish));

			RevCommit containsCommit = walk.parseCommit(resolved);
			return RevWalkUtils.findBranchesReachableFrom(containsCommit, walk, refs);
		}
	}
}
