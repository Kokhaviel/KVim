/*
 * Copyright (C) 2011-2013, Chris Aniszczyk <caniszczyk@gmail.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.api;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.Collection;
import java.util.LinkedList;

import org.eclipse.jgit.api.errors.CheckoutConflictException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuildIterator;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheCheckout;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;

public class ResetCommand extends GitCommand<Ref> {

	public enum ResetType {
		SOFT,
		MIXED,
		HARD,
		MERGE,
		KEEP
	}

	private String ref = null;
	private ResetType mode;
	private final Collection<String> filepaths = new LinkedList<>();
	private boolean isReflogDisabled;
	private final ProgressMonitor monitor = NullProgressMonitor.INSTANCE;

	public ResetCommand(Repository repo) {
		super(repo);
	}

	@Override
	public Ref call() throws GitAPIException {
		checkCallable();

		try {
			RepositoryState state = repo.getRepositoryState();
			final boolean merging = state.equals(RepositoryState.MERGING) || state.equals(RepositoryState.MERGING_RESOLVED);
			final boolean cherryPicking = state.equals(RepositoryState.CHERRY_PICKING)
					|| state.equals(RepositoryState.CHERRY_PICKING_RESOLVED);
			final boolean reverting = state.equals(RepositoryState.REVERTING) || state.equals(RepositoryState.REVERTING_RESOLVED);

			final ObjectId commitId = resolveRefToCommitId();
			if(ref != null && commitId == null) {
				throw new JGitInternalException(MessageFormat.format(JGitText.get().invalidRefName, ref));
			}

			final ObjectId commitTree;
			if(commitId != null)
				commitTree = parseCommit(commitId).getTree();
			else
				commitTree = null;

			if(!filepaths.isEmpty()) {
				resetIndexForPaths(commitTree);
				setCallable(false);
				return repo.exactRef(Constants.HEAD);
			}

			final Ref result;
			if(commitId != null) {
				final RefUpdate ru = repo.updateRef(Constants.HEAD);
				ru.setNewObjectId(commitId);

				String refName = Repository.shortenRefName(getRefOrHEAD());
				if(isReflogDisabled) {
					ru.disableRefLog();
				} else {
					String message = refName + ": updating " + Constants.HEAD;
					ru.setRefLogMessage(message, false);
				}
				if(ru.forceUpdate() == RefUpdate.Result.LOCK_FAILURE)
					throw new JGitInternalException(MessageFormat.format(
							JGitText.get().cannotLock, ru.getName()));

				ObjectId origHead = ru.getOldObjectId();
				if(origHead != null)
					repo.writeOrigHead(origHead);
			}
			result = repo.exactRef(Constants.HEAD);

			if(mode == null)
				mode = ResetType.MIXED;

			switch(mode) {
				case HARD:
					checkoutIndex(commitTree);
					break;
				case MIXED:
					resetIndex(commitTree);
					break;
				case SOFT:
					break;
				case KEEP:
				case MERGE:
					throw new UnsupportedOperationException();

			}

			if(mode != ResetType.SOFT) {
				if(merging)
					resetMerge();
				else if(cherryPicking)
					resetCherryPick();
				else if(reverting)
					resetRevert();
				else if(repo.readSquashCommitMsg() != null)
					repo.writeSquashCommitMsg(null);
			}

			setCallable(false);
			return result;
		} catch(IOException e) {
			throw new JGitInternalException(MessageFormat.format(
					JGitText.get().exceptionCaughtDuringExecutionOfResetCommand, e.getMessage()), e);
		}
	}

	private RevCommit parseCommit(ObjectId commitId) {
		try(RevWalk rw = new RevWalk(repo)) {
			return rw.parseCommit(commitId);
		} catch(IOException e) {
			throw new JGitInternalException(MessageFormat.format(JGitText.get().cannotReadCommit, commitId.toString()), e);
		}
	}

	private ObjectId resolveRefToCommitId() {
		try {
			return repo.resolve(getRefOrHEAD() + "^{commit}");
		} catch(IOException e) {
			throw new JGitInternalException(MessageFormat.format(JGitText.get().cannotRead, getRefOrHEAD()), e);
		}
	}

	public ResetCommand setRef(String ref) {
		this.ref = ref;
		return this;
	}

	public ResetCommand setMode(ResetType mode) {
		if(!filepaths.isEmpty())
			throw new JGitInternalException(MessageFormat.format(
					JGitText.get().illegalCombinationOfArguments, "[--mixed | --soft | --hard]", "<paths>..."));
		this.mode = mode;
		return this;
	}

	private String getRefOrHEAD() {
		if(ref != null) {
			return ref;
		}
		return Constants.HEAD;
	}

	private void resetIndexForPaths(ObjectId commitTree) {
		DirCache dc = null;
		try(TreeWalk tw = new TreeWalk(repo)) {
			dc = repo.lockDirCache();
			DirCacheBuilder builder = dc.builder();

			tw.addTree(new DirCacheBuildIterator(builder));
			if(commitTree != null)
				tw.addTree(commitTree);
			else
				tw.addTree(new EmptyTreeIterator());
			tw.setFilter(PathFilterGroup.createFromStrings(filepaths));
			tw.setRecursive(true);

			while(tw.next()) {
				final CanonicalTreeParser tree = tw.getTree(1);
				if(tree != null) {
					DirCacheEntry entry = new DirCacheEntry(tw.getRawPath());
					entry.setFileMode(tree.getEntryFileMode());
					entry.setObjectId(tree.getEntryObjectId());
					builder.add(entry);
				}
			}

			builder.commit();
		} catch(IOException e) {
			throw new RuntimeException(e);
		} finally {
			if(dc != null)
				dc.unlock();
		}
	}

	private void resetIndex(ObjectId commitTree) throws IOException {
		DirCache dc = repo.lockDirCache();
		try(TreeWalk walk = new TreeWalk(repo)) {
			DirCacheBuilder builder = dc.builder();

			if(commitTree != null)
				walk.addTree(commitTree);
			else
				walk.addTree(new EmptyTreeIterator());
			walk.addTree(new DirCacheIterator(dc));
			walk.setRecursive(true);

			while(walk.next()) {
				AbstractTreeIterator cIter = walk.getTree(0
				);
				if(cIter == null) continue;

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

	private void checkoutIndex(ObjectId commitTree) throws IOException,
			GitAPIException {
		DirCache dc = repo.lockDirCache();
		try {
			DirCacheCheckout checkout = new DirCacheCheckout(repo, dc, commitTree);
			checkout.setFailOnConflict(false);
			checkout.setProgressMonitor(monitor);
			try {
				checkout.checkout();
			} catch(org.eclipse.jgit.errors.CheckoutConflictException cce) {
				throw new CheckoutConflictException(checkout.getConflicts(), cce);
			}
		} finally {
			dc.unlock();
		}
	}

	private void resetMerge() throws IOException {
		repo.writeMergeHeads(null);
		repo.writeMergeCommitMsg(null);
	}

	private void resetCherryPick() throws IOException {
		repo.writeCherryPickHead(null);
		repo.writeMergeCommitMsg(null);
	}

	private void resetRevert() throws IOException {
		repo.writeRevertHead(null);
		repo.writeMergeCommitMsg(null);
	}

	@Override
	public String toString() {
		return "ResetCommand [repo=" + repo + ", ref=" + ref + ", mode=" + mode
				+ ", isReflogDisabled=" + isReflogDisabled + ", filepaths=" + filepaths + "]";
	}
}
