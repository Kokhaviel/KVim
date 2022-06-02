/*
 * Copyright (C) 2010, Christian Halstrick <christian.halstrick@sap.com> and others
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
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.revwalk.filter.SkipRevFilter;
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;

public class LogCommand extends GitCommand<Iterable<RevCommit>> {
	private final RevWalk walk;
	private boolean startSpecified = false;
	private RevFilter revFilter;

	private final List<PathFilter> pathFilters = new ArrayList<>();
	private final List<TreeFilter> excludeTreeFilters = new ArrayList<>();

	protected LogCommand(Repository repo) {
		super(repo);
		walk = new RevWalk(repo);
	}

	@Override
	public Iterable<RevCommit> call() throws GitAPIException {
		checkCallable();
		List<TreeFilter> filters = new ArrayList<>();
		if(!pathFilters.isEmpty()) {
			filters.add(AndTreeFilter.create(PathFilterGroup.create(pathFilters), TreeFilter.ANY_DIFF));
		}
		if(!excludeTreeFilters.isEmpty()) {
			for(TreeFilter f : excludeTreeFilters) {
				filters.add(AndTreeFilter.create(f, TreeFilter.ANY_DIFF));
			}
		}
		if(!filters.isEmpty()) {
			if(filters.size() == 1) {
				filters.add(TreeFilter.ANY_DIFF);
			}
			walk.setTreeFilter(AndTreeFilter.create(filters));

		}
		if(!startSpecified) {
			try {
				ObjectId headId = repo.resolve(Constants.HEAD);
				if(headId == null)
					throw new NoHeadException(
							JGitText.get().noHEADExistsAndNoExplicitStartingRevisionWasSpecified);
				add(headId);
			} catch(IOException e) {
				throw new JGitInternalException(JGitText.get().anExceptionOccurredWhileTryingToAddTheIdOfHEAD, e);
			}
		}

		if(this.revFilter != null) {
			walk.setRevFilter(this.revFilter);
		}

		setCallable(false);
		return walk;
	}

	public LogCommand add(AnyObjectId start) throws MissingObjectException,
			IncorrectObjectTypeException {
		return add(true, start);
	}

	private LogCommand add(boolean include, AnyObjectId start)
			throws MissingObjectException, IncorrectObjectTypeException,
			JGitInternalException {
		checkCallable();
		try {
			if(include) {
				walk.markStart(walk.lookupCommit(start));
				startSpecified = true;
			} else
				walk.markUninteresting(walk.lookupCommit(start));
			return this;
		} catch(MissingObjectException | IncorrectObjectTypeException e) {
			throw e;
		} catch(IOException e) {
			throw new JGitInternalException(MessageFormat.format(
					JGitText.get().exceptionOccurredDuringAddingOfOptionToALogCommand, start), e);
		}
	}
}
