/*
 * Copyright (C) 2010, Chris Aniszczyk <caniszczyk@gmail.com>
 * Copyright (C) 2011, 2020 Matthias Sohn <matthias.sohn@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.api;

import org.eclipse.jgit.api.CheckoutResult.Status;
import org.eclipse.jgit.api.errors.*;
import org.eclipse.jgit.dircache.*;
import org.eclipse.jgit.dircache.DirCacheCheckout.CheckoutMetadata;
import org.eclipse.jgit.dircache.DirCacheEditor.PathEdit;
import org.eclipse.jgit.errors.UnmergedPathException;
import org.eclipse.jgit.events.WorkingTreeModifiedEvent;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.lib.CoreConfig.EolStreamType;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.*;

import static org.eclipse.jgit.treewalk.TreeWalk.OperationType.CHECKOUT_OP;

public class CheckoutCommand extends GitCommand<Ref> {

	private String name;
	private final boolean orphan = false;
	private RevCommit startCommit;
	private CheckoutResult status;
	private final List<String> paths;
	private boolean checkoutAllPaths;
	private Set<String> actuallyModifiedPaths;
	private ProgressMonitor monitor = NullProgressMonitor.INSTANCE;

	protected CheckoutCommand(Repository repo) {
		super(repo);
		this.paths = new LinkedList<>();
	}

	@Override
	public Ref call() throws GitAPIException {
		checkCallable();
		try {
			processOptions();
			if(checkoutAllPaths || !paths.isEmpty()) {
				checkoutPaths();
				status = new CheckoutResult(Status.OK, paths);
				setCallable(false);
				return null;
			}

			Ref headRef = repo.exactRef(Constants.HEAD);
			if(headRef == null) {
				throw new UnsupportedOperationException(
						JGitText.get().cannotCheckoutFromUnbornBranch);
			}
			String shortHeadRef = getShortBranchName(headRef);
			String refLogMessage = "checkout: moving from " + shortHeadRef;
			ObjectId branch;
			if(orphan) {
				if(startCommit == null) {
					Result r = repo.updateRef(Constants.HEAD).link(
							getBranchName());
					if(!EnumSet.of(Result.NEW, Result.FORCED).contains(r))
						throw new JGitInternalException(MessageFormat.format(
								JGitText.get().checkoutUnexpectedResult,
								r.name()));
					this.status = CheckoutResult.NOT_TRIED_RESULT;
					return repo.exactRef(Constants.HEAD);
				}
				branch = getStartPointObjectId();
			} else {
				branch = repo.resolve(name);
				if(branch == null)
					throw new RefNotFoundException(MessageFormat.format(
							JGitText.get().refNotResolved, name));
			}

			RevCommit headCommit;
			RevCommit newCommit;
			try(RevWalk revWalk = new RevWalk(repo)) {
				AnyObjectId headId = headRef.getObjectId();
				headCommit = headId == null ? null
						: revWalk.parseCommit(headId);
				newCommit = revWalk.parseCommit(branch);
			}
			RevTree headTree = headCommit == null ? null : headCommit.getTree();
			DirCacheCheckout dco;
			DirCache dc = repo.lockDirCache();
			try {
				dco = new DirCacheCheckout(repo, headTree, dc,
						newCommit.getTree());
				dco.setFailOnConflict(true);
				boolean forced = false;
				dco.setForce(forced);
				dco.setProgressMonitor(monitor);
				try {
					dco.checkout();
				} catch(org.eclipse.jgit.errors.CheckoutConflictException e) {
					status = new CheckoutResult(Status.CONFLICTS, dco.getConflicts());
					throw new CheckoutConflictException(dco.getConflicts(), e);
				}
			} finally {
				dc.unlock();
			}
			Ref ref = repo.findRef(name);
			if(ref != null && !ref.getName().startsWith(Constants.R_HEADS))
				ref = null;
			String toName = Repository.shortenRefName(name);
			RefUpdate refUpdate = repo.updateRef(Constants.HEAD, ref == null);
			boolean forceRefUpdate = false;
			refUpdate.setForceUpdate(forceRefUpdate);
			refUpdate.setRefLogMessage(refLogMessage + " to " + toName, false);
			Result updateResult;
			if(ref != null)
				updateResult = refUpdate.link(ref.getName());
			else if(orphan) {
				updateResult = refUpdate.link(getBranchName());
				ref = repo.exactRef(Constants.HEAD);
			} else {
				refUpdate.setNewObjectId(newCommit);
				updateResult = refUpdate.forceUpdate();
			}

			setCallable(false);

			boolean ok = false;
			switch(updateResult) {
				case NEW:
					ok = true;
					break;
				case NO_CHANGE:
				case FAST_FORWARD:
				case FORCED:
					ok = true;
					break;
				default:
					break;
			}

			if(!ok)
				throw new JGitInternalException(MessageFormat.format(JGitText
						.get().checkoutUnexpectedResult, updateResult.name()));


			if(!dco.getToBeDeleted().isEmpty()) {
				status = new CheckoutResult(Status.NONDELETED,
						dco.getToBeDeleted(),
						dco.getRemoved());
			} else
				status = new CheckoutResult(dco.getRemoved());

			return ref;
		} catch(IOException ioe) {
			throw new JGitInternalException(ioe.getMessage(), ioe);
		} finally {
			if(status == null)
				status = CheckoutResult.ERROR_RESULT;
		}
	}

	private String getShortBranchName(Ref headRef) {
		if(headRef.isSymbolic()) {
			return Repository.shortenRefName(headRef.getTarget().getName());
		}
		ObjectId id = headRef.getObjectId();
		if(id == null) {
			throw new NullPointerException();
		}
		return id.getName();
	}

	public CheckoutCommand setProgressMonitor(ProgressMonitor monitor) {
		if(monitor == null) {
			monitor = NullProgressMonitor.INSTANCE;
		}
		this.monitor = monitor;
		return this;
	}

	protected CheckoutCommand checkoutPaths() throws IOException,
			RefNotFoundException {
		actuallyModifiedPaths = new HashSet<>();
		DirCache dc = repo.lockDirCache();
		try(RevWalk revWalk = new RevWalk(repo);
			TreeWalk treeWalk = new TreeWalk(repo,
					revWalk.getObjectReader())) {
			treeWalk.setRecursive(true);
			if(!checkoutAllPaths)
				treeWalk.setFilter(PathFilterGroup.createFromStrings(paths));
			if(isCheckoutIndex())
				checkoutPathsFromIndex(treeWalk, dc);
			else {
				RevCommit commit = revWalk.parseCommit(getStartPointObjectId());
				checkoutPathsFromCommit(treeWalk, dc, commit);
			}
		} finally {
			try {
				dc.unlock();
			} finally {
				WorkingTreeModifiedEvent event = new WorkingTreeModifiedEvent(
						actuallyModifiedPaths, null);
				actuallyModifiedPaths = null;
				if(!event.isEmpty()) {
					repo.fireEvent(event);
				}
			}
		}
		return this;
	}

	private void checkoutPathsFromIndex(TreeWalk treeWalk, DirCache dc) throws IOException {
		DirCacheIterator dci = new DirCacheIterator(dc);
		treeWalk.addTree(dci);

		String previousPath = null;

		final ObjectReader r = treeWalk.getObjectReader();
		DirCacheEditor editor = dc.editor();
		while(treeWalk.next()) {
			String path = treeWalk.getPathString();
			if(path.equals(previousPath))
				continue;

			final EolStreamType eolStreamType = treeWalk
					.getEolStreamType(CHECKOUT_OP);
			final String filterCommand = treeWalk
					.getFilterCommand(Constants.ATTR_FILTER_TYPE_SMUDGE);
			editor.add(new PathEdit(path) {
				@Override
				public void apply(DirCacheEntry ent) {
					int stage = ent.getStage();
					if(stage > DirCacheEntry.STAGE_0) {
						UnmergedPathException e = new UnmergedPathException(
								ent);
						throw new JGitInternalException(e.getMessage(), e);
					} else {
						checkoutPath(ent, r, new CheckoutMetadata(eolStreamType, filterCommand));
						actuallyModifiedPaths.add(path);
					}
				}
			});

			previousPath = path;
		}
		editor.commit();
	}

	private void checkoutPathsFromCommit(TreeWalk treeWalk, DirCache dc,
										 RevCommit commit) throws IOException {
		treeWalk.addTree(commit.getTree());
		final ObjectReader r = treeWalk.getObjectReader();
		DirCacheEditor editor = dc.editor();
		while(treeWalk.next()) {
			final ObjectId blobId = treeWalk.getObjectId(0);
			final FileMode mode = treeWalk.getFileMode(0);
			final EolStreamType eolStreamType = treeWalk
					.getEolStreamType(CHECKOUT_OP);
			final String filterCommand = treeWalk
					.getFilterCommand(Constants.ATTR_FILTER_TYPE_SMUDGE);
			final String path = treeWalk.getPathString();
			editor.add(new PathEdit(path) {
				@Override
				public void apply(DirCacheEntry ent) {
					if(ent.getStage() != DirCacheEntry.STAGE_0) {
						ent.setStage(DirCacheEntry.STAGE_0);
					}
					ent.setObjectId(blobId);
					ent.setFileMode(mode);
					checkoutPath(ent, r, new CheckoutMetadata(eolStreamType, filterCommand));
					actuallyModifiedPaths.add(path);
				}
			});
		}
		editor.commit();
	}

	private void checkoutPath(DirCacheEntry entry, ObjectReader reader, CheckoutMetadata checkoutMetadata) {
		try {
			DirCacheCheckout.checkoutEntry(repo, entry, reader, true, checkoutMetadata);
		} catch(IOException e) {
			throw new JGitInternalException(MessageFormat.format(
					JGitText.get().checkoutConflictWithFile, entry.getPathString()), e);
		}
	}

	private boolean isCheckoutIndex() {
		return startCommit == null;
	}

	private ObjectId getStartPointObjectId() throws
			RefNotFoundException, IOException {
		if(startCommit != null)
			return startCommit.getId();

		String startPointOrHead = Constants.HEAD;
		ObjectId result = repo.resolve(startPointOrHead);
		if(result == null)
			throw new RefNotFoundException(MessageFormat.format(
					JGitText.get().refNotResolved, startPointOrHead));
		return result;
	}

	private void processOptions() throws InvalidRefNameException,
			RefAlreadyExistsException, IOException {
		if(((!checkoutAllPaths && paths.isEmpty()) || orphan)
				&& (name == null || !Repository
				.isValidRefName(Constants.R_HEADS + name)))
			throw new InvalidRefNameException(MessageFormat.format(JGitText
					.get().branchNameInvalid, name == null ? "<null>" : name));

		if(orphan) {
			Ref refToCheck = repo.exactRef(getBranchName());
			if(refToCheck != null)
				throw new RefAlreadyExistsException(MessageFormat.format(JGitText.get().refAlreadyExists, name));
		}
	}

	private String getBranchName() {
		if(name.startsWith(Constants.R_REFS))
			return name;

		return Constants.R_HEADS + name;
	}

	public CheckoutCommand setName(String name) {
		checkCallable();
		this.name = name;
		return this;
	}
}
