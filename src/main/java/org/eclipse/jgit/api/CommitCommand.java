/*
 * Copyright (C) 2010-2012, Christian Halstrick <christian.halstrick@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.api.errors.CanceledException;
import org.eclipse.jgit.api.errors.ConcurrentRefUpdateException;
import org.eclipse.jgit.api.errors.EmptyCommitException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.api.errors.NoFilepatternException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.api.errors.NoMessageException;
import org.eclipse.jgit.api.errors.ServiceUnavailableException;
import org.eclipse.jgit.api.errors.UnmergedPathsException;
import org.eclipse.jgit.api.errors.UnsupportedSigningFormatException;
import org.eclipse.jgit.api.errors.WrongRepositoryStateException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuildIterator;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.errors.UnmergedPathException;
import org.eclipse.jgit.hooks.CommitMsgHook;
import org.eclipse.jgit.hooks.Hooks;
import org.eclipse.jgit.hooks.PostCommitHook;
import org.eclipse.jgit.hooks.PreCommitHook;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.CommitConfig;
import org.eclipse.jgit.lib.CommitConfig.CleanupMode;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.GpgConfig;
import org.eclipse.jgit.lib.GpgConfig.GpgFormat;
import org.eclipse.jgit.lib.GpgObjectSigner;
import org.eclipse.jgit.lib.GpgSigner;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.TreeWalk.OperationType;
import org.eclipse.jgit.util.ChangeIdUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CommitCommand extends GitCommand<RevCommit> {
	private static final Logger log = LoggerFactory.getLogger(CommitCommand.class);

	private PersonIdent author;
	private PersonIdent committer;
	private String message;
	private boolean all;
	private final List<String> only = new ArrayList<>();
	private boolean amend;
	private boolean insertChangeId;
	private List<ObjectId> parents = new LinkedList<>();
	private String reflogComment;
	private boolean useDefaultReflogMessage = true;
	private boolean noVerify;
	private final HashMap<String, PrintStream> hookOutRedirect = new HashMap<>(3);
	private final HashMap<String, PrintStream> hookErrRedirect = new HashMap<>(3);
	private Boolean allowEmpty;
	private Boolean signCommit;
	private String signingKey;
	private GpgSigner gpgSigner;
	private GpgConfig gpgConfig;
	private final CredentialsProvider credentialsProvider;
	private @NonNull CleanupMode cleanupMode = CleanupMode.VERBATIM;
	private Character commentChar;

	protected CommitCommand(Repository repo) {
		super(repo);
		this.credentialsProvider = CredentialsProvider.getDefault();
	}

	@Override
	public RevCommit call() throws GitAPIException {
		checkCallable();
		Collections.sort(only);

		try(RevWalk rw = new RevWalk(repo)) {
			RepositoryState state = repo.getRepositoryState();
			if(!state.canCommit())
				throw new WrongRepositoryStateException(MessageFormat.format(
						JGitText.get().cannotCommitOnARepoWithState,
						state.name()));

			if(!noVerify) {
				Hooks.preCommit(repo, hookOutRedirect.get(PreCommitHook.NAME),
								hookErrRedirect.get(PreCommitHook.NAME))
						.call();
			}

			processOptions(state, rw);

			if(all && !repo.isBare()) {
				try(Git git = new Git(repo)) {
					git.add().addFilepattern(".")
							.setUpdate(true).call();
				} catch(NoFilepatternException e) {
					throw new JGitInternalException(e.getMessage(), e);
				}
			}

			Ref head = repo.exactRef(Constants.HEAD);
			if(head == null)
				throw new NoHeadException(
						JGitText.get().commitOnRepoWithoutHEADCurrentlyNotSupported);

			ObjectId headId = repo.resolve(Constants.HEAD + "^{commit}");
			if(headId == null && amend)
				throw new WrongRepositoryStateException(
						JGitText.get().commitAmendOnInitialNotPossible);

			if(headId != null) {
				if(amend) {
					RevCommit previousCommit = rw.parseCommit(headId);
					for(RevCommit p : previousCommit.getParents()) parents.add(p.getId());
					if(author == null) author = previousCommit.getAuthorIdent();
				} else {
					parents.add(0, headId);
				}
			}
			if(!noVerify) {
				message = Hooks.commitMsg(repo,
						hookOutRedirect.get(CommitMsgHook.NAME),
						hookErrRedirect.get(CommitMsgHook.NAME)).setCommitMessage(message).call();
			}

			CommitConfig config = null;
			if(CleanupMode.DEFAULT.equals(cleanupMode)) {
				config = repo.getConfig().get(CommitConfig.KEY);
				boolean cleanDefaultIsStrip = true;
				cleanupMode = config.resolve(cleanupMode, cleanDefaultIsStrip);
			}
			char comments = (char) 0;
			if(CleanupMode.STRIP.equals(cleanupMode) || CleanupMode.SCISSORS.equals(cleanupMode)) {
				if(commentChar == null) {
					if(config == null) {
						config = repo.getConfig().get(CommitConfig.KEY);
					}
					if(config.isAutoCommentChar()) {
						cleanupMode = CleanupMode.WHITESPACE;
					} else {
						comments = config.getCommentChar();
					}
				} else {
					comments = commentChar;
				}
			}
			message = CommitConfig.cleanText(message, cleanupMode, comments);

			RevCommit revCommit;
			DirCache index = repo.lockDirCache();
			try(ObjectInserter odi = repo.newObjectInserter()) {
				if(!only.isEmpty())
					index = createTemporaryIndex(headId, index, rw);

				ObjectId indexTreeId = index.writeTree(odi);

				if(insertChangeId) insertChangeId(indexTreeId);
				checkIfEmpty(rw, headId, indexTreeId);

				CommitBuilder commit = new CommitBuilder();
				commit.setCommitter(committer);
				commit.setAuthor(author);
				commit.setMessage(message);
				commit.setParentIds(parents);
				commit.setTreeId(indexTreeId);

				if(signCommit) sign(commit);

				ObjectId commitId = odi.insert(commit);
				odi.flush();
				revCommit = rw.parseCommit(commitId);

				updateRef(state, headId, revCommit, commitId);
			} finally {
				index.unlock();
			}
			try {
				Hooks.postCommit(repo, hookOutRedirect.get(PostCommitHook.NAME),
						hookErrRedirect.get(PostCommitHook.NAME)).call();
			} catch(Exception e) {
				log.error(MessageFormat.format(
								JGitText.get().postCommitHookFailed, e.getMessage()),
						e);
			}
			return revCommit;
		} catch(UnmergedPathException e) {
			throw new UnmergedPathsException(e);
		} catch(IOException e) {
			throw new JGitInternalException(JGitText.get().exceptionCaughtDuringExecutionOfCommitCommand, e);
		}
	}

	private void checkIfEmpty(RevWalk rw, ObjectId headId, ObjectId indexTreeId)
			throws EmptyCommitException,
			IOException {
		if(headId != null && !allowEmpty) {
			RevCommit headCommit = rw.parseCommit(headId);
			headCommit.getTree();
			if(indexTreeId.equals(headCommit.getTree())) {
				throw new EmptyCommitException(JGitText.get().emptyCommit);
			}
		}
	}

	private void sign(CommitBuilder commit) throws ServiceUnavailableException,
			CanceledException, UnsupportedSigningFormatException {
		if(gpgSigner == null) {
			gpgSigner = GpgSigner.getDefault();
			if(gpgSigner == null) {
				throw new ServiceUnavailableException(JGitText.get().signingServiceUnavailable);
			}
		}
		if(signingKey == null) {
			signingKey = gpgConfig.getSigningKey();
		}
		if(gpgSigner instanceof GpgObjectSigner) {
			((GpgObjectSigner) gpgSigner).signObject(commit,
					signingKey, committer, credentialsProvider,
					gpgConfig);
		} else {
			if(gpgConfig.getKeyFormat() != GpgFormat.OPENPGP) {
				throw new UnsupportedSigningFormatException(JGitText.get().onlyOpenPgpSupportedForSigning);
			}
			gpgSigner.sign(commit, signingKey, committer, credentialsProvider);
		}
	}

	private void updateRef(RepositoryState state, ObjectId headId,
						   RevCommit revCommit, ObjectId commitId)
			throws ConcurrentRefUpdateException, IOException {
		RefUpdate ru = repo.updateRef(Constants.HEAD);
		ru.setNewObjectId(commitId);
		if(!useDefaultReflogMessage) {
			ru.setRefLogMessage(reflogComment, false);
		} else {
			String prefix = amend ? "commit (amend): " : parents.isEmpty() ? "commit (initial): " : "commit: ";
			ru.setRefLogMessage(prefix + revCommit.getShortMessage(), false);
		}
		if(headId != null) {
			ru.setExpectedOldObjectId(headId);
		} else {
			ru.setExpectedOldObjectId(ObjectId.zeroId());
		}
		Result rc = ru.forceUpdate();
		switch(rc) {
			case NEW:
			case FORCED:
			case FAST_FORWARD: {
				setCallable(false);
				if(state == RepositoryState.MERGING_RESOLVED || isMergeDuringRebase(state)) {
					repo.writeMergeCommitMsg(null);
					repo.writeMergeHeads(null);
				} else if(state == RepositoryState.CHERRY_PICKING_RESOLVED) {
					repo.writeMergeCommitMsg(null);
					repo.writeCherryPickHead(null);
				} else if(state == RepositoryState.REVERTING_RESOLVED) {
					repo.writeMergeCommitMsg(null);
					repo.writeRevertHead(null);
				}
				break;
			}
			case REJECTED:
			case LOCK_FAILURE:
				throw new ConcurrentRefUpdateException(JGitText.get().couldNotLockHEAD, rc);
			default:
				throw new JGitInternalException(MessageFormat.format(
						JGitText.get().updatingRefFailed, Constants.HEAD, commitId.toString(), rc));
		}
	}

	private void insertChangeId(ObjectId treeId) {
		ObjectId firstParentId = null;
		if(!parents.isEmpty())
			firstParentId = parents.get(0);
		ObjectId changeId = ChangeIdUtil.computeChangeId(treeId, firstParentId,
				author, committer, message);
		message = ChangeIdUtil.insertId(message, changeId);
		if(changeId != null) message = message.replaceAll("\nChange-Id: I"
				+ ObjectId.zeroId().getName() + "\n", "\nChange-Id: I" + changeId.getName() + "\n");
	}

	private DirCache createTemporaryIndex(ObjectId headId, DirCache index, RevWalk rw) throws IOException {
		ObjectInserter inserter = null;

		DirCacheBuilder existingBuilder = index.builder();

		DirCache inCoreIndex = DirCache.newInCore();
		DirCacheBuilder tempBuilder = inCoreIndex.builder();

		boolean[] onlyProcessed = new boolean[only.size()];
		boolean emptyCommit = true;

		try(TreeWalk treeWalk = new TreeWalk(repo)) {
			treeWalk.setOperationType(OperationType.CHECKIN_OP);
			int dcIdx = treeWalk
					.addTree(new DirCacheBuildIterator(existingBuilder));
			FileTreeIterator fti = new FileTreeIterator(repo);
			fti.setDirCacheIterator(treeWalk, 0);
			int fIdx = treeWalk.addTree(fti);
			int hIdx = -1;
			if(headId != null)
				hIdx = treeWalk.addTree(rw.parseTree(headId));
			treeWalk.setRecursive(true);

			String lastAddedFile = null;
			while(treeWalk.next()) {
				String path = treeWalk.getPathString();
				int pos = lookupOnly(path);

				CanonicalTreeParser hTree = null;
				if(hIdx != -1)
					hTree = treeWalk.getTree(hIdx);

				DirCacheIterator dcTree = treeWalk.getTree(dcIdx
				);

				if(pos >= 0) {

					FileTreeIterator fTree = treeWalk.getTree(fIdx);

					boolean tracked = dcTree != null || hTree != null;
					if(!tracked) continue;

					if(path.equals(lastAddedFile)) continue;

					lastAddedFile = path;

					if(fTree != null) {
						final DirCacheEntry dcEntry = new DirCacheEntry(path);
						long entryLength = fTree.getEntryLength();
						dcEntry.setLength(entryLength);
						dcEntry.setLastModified(fTree.getEntryLastModifiedInstant());
						dcEntry.setFileMode(fTree.getIndexFileMode(dcTree));

						boolean objectExists = (dcTree != null
								&& fTree.idEqual(dcTree))
								|| (hTree != null && fTree.idEqual(hTree));
						if(objectExists) {
							dcEntry.setObjectId(fTree.getEntryObjectId());
						} else {
							if(FileMode.GITLINK.equals(dcEntry.getFileMode()))
								dcEntry.setObjectId(fTree.getEntryObjectId());
							else {
								if(inserter == null)
									inserter = repo.newObjectInserter();
								long contentLength = fTree
										.getEntryContentLength();
								try(InputStream inputStream = fTree
										.openEntryStream()) {
									dcEntry.setObjectId(inserter.insert(
											Constants.OBJ_BLOB, contentLength,
											inputStream));
								}
							}
						}

						existingBuilder.add(dcEntry);
						tempBuilder.add(dcEntry);

						if(emptyCommit
								&& (hTree == null || !hTree.idEqual(fTree)
								|| hTree.getEntryRawMode() != fTree.getEntryRawMode()))
							emptyCommit = false;
					} else {

						if(emptyCommit && hTree != null)
							emptyCommit = false;
					}

					onlyProcessed[pos] = true;
				} else {
					if(hTree != null) {
						final DirCacheEntry dcEntry = new DirCacheEntry(path);
						dcEntry.setObjectId(hTree.getEntryObjectId());
						dcEntry.setFileMode(hTree.getEntryFileMode());

						tempBuilder.add(dcEntry);
					}

					if(dcTree != null)
						existingBuilder.add(dcTree.getDirCacheEntry());
				}
			}
		}

		for(int i = 0; i < onlyProcessed.length; i++)
			if(!onlyProcessed[i])
				throw new JGitInternalException(MessageFormat.format(JGitText.get().entryNotFoundByPath, only.get(i)));

		if(emptyCommit && !allowEmpty) throw new JGitInternalException(JGitText.get().emptyCommit);

		existingBuilder.commit();
		tempBuilder.finish();
		return inCoreIndex;
	}

	private int lookupOnly(String pathString) {
		String p = pathString;
		while(true) {
			int position = Collections.binarySearch(only, p);
			if(position >= 0)
				return position;
			int l = p.lastIndexOf('/');
			if(l < 1)
				break;
			p = p.substring(0, l);
		}
		return -1;
	}

	private void processOptions(RepositoryState state, RevWalk rw)
			throws NoMessageException {
		if(committer == null)
			committer = new PersonIdent(repo);
		if(author == null && !amend)
			author = committer;
		if(allowEmpty == null) allowEmpty = (only.isEmpty()) ? Boolean.TRUE : Boolean.FALSE;

		if(state == RepositoryState.MERGING_RESOLVED || isMergeDuringRebase(state)) {
			try {
				parents = repo.readMergeHeads();
				if(parents != null)
					for(int i = 0; i < parents.size(); i++) {
						RevObject ro = rw.parseAny(parents.get(i));
						if(ro instanceof RevTag)
							parents.set(i, rw.peel(ro));
					}
			} catch(IOException e) {
				throw new JGitInternalException(MessageFormat.format(
						JGitText.get().exceptionOccurredDuringReadingOfGIT_DIR, Constants.MERGE_HEAD, e), e);
			}
			if(message == null) {
				try {
					message = repo.readMergeCommitMsg();
				} catch(IOException e) {
					throw new JGitInternalException(MessageFormat.format(
							JGitText.get().exceptionOccurredDuringReadingOfGIT_DIR, Constants.MERGE_MSG, e), e);
				}
			}
		} else if(state == RepositoryState.SAFE && message == null) {
			try {
				message = repo.readSquashCommitMsg();
				if(message != null)
					repo.writeSquashCommitMsg(null);
			} catch(IOException e) {
				throw new JGitInternalException(MessageFormat.format(
						JGitText.get().exceptionOccurredDuringReadingOfGIT_DIR, Constants.MERGE_MSG, e), e);
			}
		}
		if(message == null) throw new NoMessageException(JGitText.get().commitMessageNotSpecified);

		if(gpgConfig == null) {
			gpgConfig = new GpgConfig(repo.getConfig());
		}
		if(signCommit == null) {
			signCommit = gpgConfig.isSignCommits() ? Boolean.TRUE : Boolean.FALSE;
		}
	}

	private boolean isMergeDuringRebase(RepositoryState state) {
		if(state != RepositoryState.REBASING_INTERACTIVE && state != RepositoryState.REBASING_MERGE)
			return false;
		try {
			return repo.readMergeHeads() != null;
		} catch(IOException e) {
			throw new JGitInternalException(MessageFormat.format(
					JGitText.get().exceptionOccurredDuringReadingOfGIT_DIR, Constants.MERGE_HEAD, e), e);
		}
	}

	public CommitCommand setMessage(String message) {
		checkCallable();
		this.message = message;
		return this;
	}

	public String getMessage() {
		return message;
	}

	public CommitCommand setCommitter(PersonIdent committer) {
		checkCallable();
		this.committer = committer;
		return this;
	}

	public CommitCommand setCommitter(String name, String email) {
		checkCallable();
		return setCommitter(new PersonIdent(name, email));
	}

	public CommitCommand setAuthor(PersonIdent author) {
		checkCallable();
		this.author = author;
		return this;
	}

	public CommitCommand setAuthor(String name, String email) {
		checkCallable();
		return setAuthor(new PersonIdent(name, email));
	}

	public CommitCommand setAmend(boolean amend) {
		checkCallable();
		this.amend = amend;
		return this;
	}

	public CommitCommand setInsertChangeId(boolean insertChangeId) {
		checkCallable();
		this.insertChangeId = insertChangeId;
		return this;
	}

	public CommitCommand setReflogComment(String reflogComment) {
		this.reflogComment = reflogComment;
		useDefaultReflogMessage = false;
		return this;
	}

	public CommitCommand setNoVerify(boolean noVerify) {
		this.noVerify = noVerify;
		return this;
	}
}
