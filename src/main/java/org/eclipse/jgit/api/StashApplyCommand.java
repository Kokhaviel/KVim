/*
 * Copyright (C) 2012, 2021 GitHub Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.api;

import static org.eclipse.jgit.treewalk.TreeWalk.OperationType.CHECKOUT_OP;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRefNameException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.api.errors.StashApplyFailureException;
import org.eclipse.jgit.api.errors.WrongRepositoryStateException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheCheckout;
import org.eclipse.jgit.dircache.DirCacheCheckout.CheckoutMetadata;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.errors.CheckoutConflictException;
import org.eclipse.jgit.events.WorkingTreeModifiedEvent;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.CoreConfig.EolStreamType;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.merge.ContentMergeStrategy;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.Merger;
import org.eclipse.jgit.merge.ResolveMerger;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;

public class StashApplyCommand extends GitCommand<ObjectId> {

	private static final String DEFAULT_REF = Constants.STASH + "@{0}";

	private String stashRef;
	private boolean ignoreRepositoryState;
	private MergeStrategy strategy = MergeStrategy.RECURSIVE;
	private ContentMergeStrategy contentStrategy;

	public StashApplyCommand(Repository repo) {
		super(repo);
	}

	public StashApplyCommand setStashRef(String stashRef) {
		this.stashRef = stashRef;
		return this;
	}

	public StashApplyCommand ignoreRepositoryState(boolean willIgnoreRepositoryState) {
		this.ignoreRepositoryState = willIgnoreRepositoryState;
		return this;
	}

	private ObjectId getStashId() throws GitAPIException {
		final String revision = stashRef != null ? stashRef : DEFAULT_REF;
		final ObjectId stashId;
		try {
			stashId = repo.resolve(revision);
		} catch(IOException e) {
			throw new InvalidRefNameException(MessageFormat.format(JGitText.get().stashResolveFailed, revision), e);
		}
		if(stashId == null)
			throw new InvalidRefNameException(MessageFormat.format(JGitText.get().stashResolveFailed, revision));
		return stashId;
	}

	@Override
	public ObjectId call() throws GitAPIException {
		checkCallable();

		if(!ignoreRepositoryState && repo.getRepositoryState() != RepositoryState.SAFE)
			throw new WrongRepositoryStateException(MessageFormat.format(
					JGitText.get().stashApplyOnUnsafeRepository, repo.getRepositoryState()));

		try(ObjectReader reader = repo.newObjectReader();
			RevWalk revWalk = new RevWalk(reader)) {

			ObjectId headCommit = repo.resolve(Constants.HEAD);
			if(headCommit == null) throw new NoHeadException(JGitText.get().stashApplyWithoutHead);

			final ObjectId stashId = getStashId();
			RevCommit stashCommit = revWalk.parseCommit(stashId);
			if(stashCommit.getParentCount() < 2 || stashCommit.getParentCount() > 3)
				throw new JGitInternalException(MessageFormat.format(JGitText.get().stashCommitIncorrectNumberOfParents,
						stashId.name(), stashCommit.getParentCount()));

			ObjectId headTree = repo.resolve(Constants.HEAD + "^{tree}");
			ObjectId stashIndexCommit = revWalk.parseCommit(stashCommit
					.getParent(1));
			ObjectId stashHeadCommit = stashCommit.getParent(0);
			ObjectId untrackedCommit = null;
			if(stashCommit.getParentCount() == 3)
				untrackedCommit = revWalk.parseCommit(stashCommit.getParent(2));

			Merger merger = strategy.newMerger(repo);
			boolean mergeSucceeded;
			if(merger instanceof ResolveMerger) {
				ResolveMerger resolveMerger = (ResolveMerger) merger;
				resolveMerger.setCommitNames(new String[] {"stashed HEAD", "HEAD", "stash"});
				resolveMerger.setBase(stashHeadCommit);
				resolveMerger.setWorkingTreeIterator(new FileTreeIterator(repo));
				resolveMerger.setContentMergeStrategy(contentStrategy);
				mergeSucceeded = resolveMerger.merge(headCommit, stashCommit);
				List<String> modifiedByMerge = resolveMerger.getModifiedFiles();
				if(!modifiedByMerge.isEmpty()) {
					repo.fireEvent(new WorkingTreeModifiedEvent(modifiedByMerge,
							null));
				}
			} else {
				mergeSucceeded = merger.merge(headCommit, stashCommit);
			}
			if(mergeSucceeded) {
				DirCache dc = repo.lockDirCache();
				DirCacheCheckout dco = new DirCacheCheckout(repo, headTree,
						dc, merger.getResultTreeId());
				dco.setFailOnConflict(true);
				dco.checkout();
				{
					Merger ixMerger = strategy.newMerger(repo, true);
					if(ixMerger instanceof ResolveMerger) {
						ResolveMerger resolveMerger = (ResolveMerger) ixMerger;
						resolveMerger.setCommitNames(new String[] {"stashed HEAD", "HEAD", "stashed index"});
						resolveMerger.setBase(stashHeadCommit);
						resolveMerger.setContentMergeStrategy(contentStrategy);
					}
					boolean ok = ixMerger.merge(headCommit, stashIndexCommit);
					if(ok) {
						resetIndex(revWalk
								.parseTree(ixMerger.getResultTreeId()));
					} else {
						throw new StashApplyFailureException(
								JGitText.get().stashApplyConflict);
					}
				}

				if(untrackedCommit != null) {
					Merger untrackedMerger = strategy.newMerger(repo, true);
					if(untrackedMerger instanceof ResolveMerger) {
						ResolveMerger resolveMerger = (ResolveMerger) untrackedMerger;
						resolveMerger.setCommitNames(new String[] {"null", "HEAD", "untracked files"});
						resolveMerger.setBase(null);
						resolveMerger.setContentMergeStrategy(contentStrategy);
					}
					boolean ok = untrackedMerger.merge(headCommit,
							untrackedCommit);
					if(ok) {
						try {
							RevTree untrackedTree = revWalk
									.parseTree(untrackedCommit);
							resetUntracked(untrackedTree);
						} catch(CheckoutConflictException e) {
							throw new StashApplyFailureException(
									JGitText.get().stashApplyConflict, e);
						}
					} else {
						throw new StashApplyFailureException(
								JGitText.get().stashApplyConflict);
					}
				}
			} else {
				throw new StashApplyFailureException(
						JGitText.get().stashApplyConflict);
			}
			return stashId;

		} catch(IOException e) {
			throw new JGitInternalException(JGitText.get().stashApplyFailed, e);
		}
	}

	public StashApplyCommand setStrategy(MergeStrategy strategy) {
		this.strategy = strategy;
		return this;
	}

	private void resetIndex(RevTree tree) throws IOException {
		DirCache dc = repo.lockDirCache();
		try(TreeWalk walk = new TreeWalk(repo)) {
			DirCacheBuilder builder = dc.builder();

			walk.addTree(tree);
			walk.addTree(new DirCacheIterator(dc));
			walk.setRecursive(true);

			while(walk.next()) {
				AbstractTreeIterator cIter = walk.getTree(0
				);
				if(cIter == null) {
					continue;
				}

				final DirCacheEntry entry = new DirCacheEntry(walk.getRawPath());
				entry.setFileMode(cIter.getEntryFileMode());
				entry.setObjectIdFromRaw(cIter.idBuffer(), cIter.idOffset());

				DirCacheIterator dcIter = walk.getTree(1
				);
				if(dcIter != null && dcIter.idEqual(cIter)) {
					DirCacheEntry indexEntry = dcIter.getDirCacheEntry();
					entry.setLastModified(indexEntry.getLastModifiedInstant());
					entry.setLength(indexEntry.getLength());
				}

				builder.add(entry);
			}

			builder.commit();
		} finally {
			dc.unlock();
		}
	}

	private void resetUntracked(RevTree tree) throws IOException {
		Set<String> actuallyModifiedPaths = new HashSet<>();
		try(TreeWalk walk = new TreeWalk(repo)) {
			walk.addTree(tree);
			walk.addTree(new FileTreeIterator(repo));
			walk.setRecursive(true);

			final ObjectReader reader = walk.getObjectReader();

			while(walk.next()) {
				final AbstractTreeIterator cIter = walk.getTree(0);
				if(cIter == null) continue;

				final EolStreamType eolStreamType = walk.getEolStreamType(CHECKOUT_OP);
				final DirCacheEntry entry = new DirCacheEntry(walk.getRawPath());
				entry.setFileMode(cIter.getEntryFileMode());
				entry.setObjectIdFromRaw(cIter.idBuffer(), cIter.idOffset());

				FileTreeIterator fIter = walk
						.getTree(1);
				if(fIter != null) {
					if(fIter.isModified(entry, true, reader)) {
						throw new CheckoutConflictException(entry.getPathString());
					}
				}

				checkoutPath(entry, reader, new CheckoutMetadata(eolStreamType, null));
				actuallyModifiedPaths.add(entry.getPathString());
			}
		} finally {
			if(!actuallyModifiedPaths.isEmpty()) {
				repo.fireEvent(new WorkingTreeModifiedEvent(actuallyModifiedPaths, null));
			}
		}
	}

	private void checkoutPath(DirCacheEntry entry, ObjectReader reader, CheckoutMetadata checkoutMetadata) {
		try {
			DirCacheCheckout.checkoutEntry(repo, entry, reader, true, checkoutMetadata);
		} catch(IOException e) {
			throw new JGitInternalException(MessageFormat.format(
					JGitText.get().checkoutConflictWithFile, entry.getPathString()), e);
		}
	}
}
