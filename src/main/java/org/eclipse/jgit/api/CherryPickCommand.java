/*
 * Copyright (C) 2010, 2021 Christian Halstrick <christian.halstrick@sap.com> and others
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
import org.eclipse.jgit.api.errors.MultipleParentsNotAllowedException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.dircache.DirCacheCheckout;
import org.eclipse.jgit.events.WorkingTreeModifiedEvent;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.lib.Ref.Storage;
import org.eclipse.jgit.merge.*;
import org.eclipse.jgit.merge.ResolveMerger.MergeFailureReason;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.FileTreeIterator;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static org.eclipse.jgit.lib.Constants.OBJECT_ID_ABBREV_STRING_LENGTH;

public class CherryPickCommand extends GitCommand<CherryPickResult> {

	private String reflogPrefix = "cherry-pick:";
	private final List<Ref> commits = new LinkedList<>();
	private String ourCommitName = null;
	private MergeStrategy strategy = MergeStrategy.RECURSIVE;
	private ContentMergeStrategy contentStrategy;
	private Integer mainlineParentNumber;
	private boolean noCommit = false;
	private final ProgressMonitor monitor = NullProgressMonitor.INSTANCE;

	protected CherryPickCommand(Repository repo) {
		super(repo);
	}

	@Override
	public CherryPickResult call() throws GitAPIException {
		RevCommit newHead;
		List<Ref> cherryPickedRefs = new LinkedList<>();
		checkCallable();

		try(RevWalk revWalk = new RevWalk(repo)) {

			Ref headRef = repo.exactRef(Constants.HEAD);
			if(headRef == null) {
				throw new NoHeadException(
						JGitText.get().commitOnRepoWithoutHEADCurrentlyNotSupported);
			}

			newHead = revWalk.parseCommit(headRef.getObjectId());

			for(Ref src : commits) {
				ObjectId srcObjectId = src.getPeeledObjectId();
				if(srcObjectId == null) {
					srcObjectId = src.getObjectId();
				}
				RevCommit srcCommit = revWalk.parseCommit(srcObjectId);

				final RevCommit srcParent = getParentCommit(srcCommit, revWalk);

				String ourName = calculateOurName(headRef);
				String cherryPickName = srcCommit.getId().abbreviate(OBJECT_ID_ABBREV_STRING_LENGTH).name()
						+ " " + srcCommit.getShortMessage();

				Merger merger = strategy.newMerger(repo);
				merger.setProgressMonitor(monitor);
				boolean noProblems;
				Map<String, MergeFailureReason> failingPaths = null;
				List<String> unmergedPaths = null;
				if(merger instanceof ResolveMerger) {
					ResolveMerger resolveMerger = (ResolveMerger) merger;
					resolveMerger.setContentMergeStrategy(contentStrategy);
					resolveMerger.setCommitNames(
							new String[] {"BASE", ourName, cherryPickName});
					resolveMerger
							.setWorkingTreeIterator(new FileTreeIterator(repo));
					resolveMerger.setBase(srcParent.getTree());
					noProblems = merger.merge(newHead, srcCommit);
					failingPaths = resolveMerger.getFailingPaths();
					unmergedPaths = resolveMerger.getUnmergedPaths();
					if(!resolveMerger.getModifiedFiles().isEmpty()) {
						repo.fireEvent(new WorkingTreeModifiedEvent(
								resolveMerger.getModifiedFiles(), null));
					}
				} else {
					noProblems = merger.merge(newHead, srcCommit);
				}
				if(noProblems) {
					if(AnyObjectId.isEqual(newHead.getTree().getId(),
							merger.getResultTreeId())) {
						continue;
					}
					DirCacheCheckout dco = new DirCacheCheckout(repo,
							newHead.getTree(), repo.lockDirCache(),
							merger.getResultTreeId());
					dco.setFailOnConflict(true);
					dco.setProgressMonitor(monitor);
					dco.checkout();
					if(!noCommit) {
						try(Git git = new Git(getRepository())) {
							newHead = git.commit()
									.setMessage(srcCommit.getFullMessage())
									.setReflogComment(reflogPrefix + " "
											+ srcCommit.getShortMessage())
									.setAuthor(srcCommit.getAuthorIdent())
									.setNoVerify(true).call();
						}
					}
					cherryPickedRefs.add(src);
				} else {
					if(failingPaths != null && !failingPaths.isEmpty()) {
						return new CherryPickResult();
					}

					String message;
					if(unmergedPaths != null) {
						CommitConfig cfg = repo.getConfig()
								.get(CommitConfig.KEY);
						message = srcCommit.getFullMessage();
						char commentChar = cfg.getCommentChar(message);
						message = new MergeMessageFormatter().formatWithConflicts(message, unmergedPaths, commentChar);
					} else {
						message = srcCommit.getFullMessage();
					}

					if(!noCommit) {
						repo.writeCherryPickHead(srcCommit.getId());
					}
					repo.writeMergeCommitMsg(message);

					return CherryPickResult.CONFLICT;
				}
			}
		} catch(IOException e) {
			throw new JGitInternalException(
					MessageFormat.format(
							JGitText.get().exceptionCaughtDuringExecutionOfCherryPickCommand, e), e);
		}
		return new CherryPickResult(newHead);
	}

	private RevCommit getParentCommit(RevCommit srcCommit, RevWalk revWalk)
			throws MultipleParentsNotAllowedException,
			IOException {
		final RevCommit srcParent;
		if(mainlineParentNumber == null) {
			if(srcCommit.getParentCount() != 1)
				throw new MultipleParentsNotAllowedException(
						MessageFormat.format(
								JGitText.get().canOnlyCherryPickCommitsWithOneParent,
								srcCommit.name(),
								srcCommit.getParentCount()));
			srcParent = srcCommit.getParent(0);
		} else {
			if(mainlineParentNumber > srcCommit.getParentCount()) {
				throw new JGitInternalException(MessageFormat.format(
						JGitText.get().commitDoesNotHaveGivenParent, srcCommit,
						mainlineParentNumber));
			}
			srcParent = srcCommit
					.getParent(mainlineParentNumber - 1);
		}

		revWalk.parseHeaders(srcParent);
		return srcParent;
	}

	public CherryPickCommand include(Ref commit) {
		checkCallable();
		commits.add(commit);
		return this;
	}

	public CherryPickCommand include(AnyObjectId commit) {
		return include(commit.getName(), commit);
	}

	public CherryPickCommand include(String name, AnyObjectId commit) {
		return include(new ObjectIdRef.Unpeeled(Storage.LOOSE, name,
				commit.copy()));
	}

	public CherryPickCommand setOurCommitName(String ourCommitName) {
		this.ourCommitName = ourCommitName;
		return this;
	}

	public CherryPickCommand setReflogPrefix(String prefix) {
		this.reflogPrefix = prefix;
		return this;
	}

	public CherryPickCommand setStrategy(MergeStrategy strategy) {
		this.strategy = strategy;
		return this;
	}

	public CherryPickCommand setContentMergeStrategy(
			ContentMergeStrategy strategy) {
		this.contentStrategy = strategy;
		return this;
	}

	public CherryPickCommand setMainlineParentNumber(int mainlineParentNumber) {
		this.mainlineParentNumber = mainlineParentNumber;
		return this;
	}

	public CherryPickCommand setNoCommit(boolean noCommit) {
		this.noCommit = noCommit;
		return this;
	}

	private String calculateOurName(Ref headRef) {
		if(ourCommitName != null)
			return ourCommitName;

		String targetRefName = headRef.getTarget().getName();
		return Repository.shortenRefName(targetRefName);
	}

	@Override
	public String toString() {
		return "CherryPickCommand [repo=" + repo + ",\ncommits=" + commits + ",\nmainlineParentNumber=" + mainlineParentNumber
				+ ", noCommit=" + noCommit + ", ourCommitName=" + ourCommitName + ", reflogPrefix=" + reflogPrefix + ", strategy=" + strategy + "]";
	}

}
