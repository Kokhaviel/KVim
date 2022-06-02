/*
 * Copyright (C) 2010, 2013 Mathias Kinzler <mathias.kinzler@sap.com>
 * Copyright (C) 2016, 2021 Laurent Delaigue <laurent.delaigue@obeo.fr> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.api;

import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.api.RebaseResult.Status;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.errors.*;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheCheckout;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.lib.CommitConfig.CleanupMode;
import org.eclipse.jgit.lib.RebaseTodoLine.Action;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.merge.ContentMergeStrategy;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.submodule.SubmoduleWalk.IgnoreSubmoduleMode;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.eclipse.jgit.util.FileUtils;
import org.eclipse.jgit.util.IO;
import org.eclipse.jgit.util.RawParseUtils;

import java.io.*;
import java.text.MessageFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;

public class RebaseCommand extends GitCommand<RebaseResult> {

	public static final String REBASE_MERGE = "rebase-merge";
	private static final String REBASE_APPLY = "rebase-apply";
	public static final String STOPPED_SHA = "stopped-sha";
	private static final String AUTHOR_SCRIPT = "author-script";
	private static final String DONE = "done";
	private static final String GIT_AUTHOR_DATE = "GIT_AUTHOR_DATE";
	private static final String GIT_AUTHOR_EMAIL = "GIT_AUTHOR_EMAIL";
	private static final String GIT_AUTHOR_NAME = "GIT_AUTHOR_NAME";
	private static final String GIT_REBASE_TODO = "git-rebase-todo";
	private static final String HEAD_NAME = "head-name";
	private static final String INTERACTIVE = "interactive";
	private static final String QUIET = "quiet";
	private static final String MESSAGE = "message";
	private static final String ONTO = "onto";
	private static final String ONTO_NAME = "onto_name";
	private static final String PATCH = "patch";
	private static final String REBASE_HEAD = "orig-head";
	private static final String REBASE_HEAD_LEGACY = "head";
	private static final String AMEND = "amend";
	private static final String MESSAGE_FIXUP = "message-fixup";
	private static final String MESSAGE_SQUASH = "message-squash";
	private static final String AUTOSTASH = "autostash";
	private static final String AUTOSTASH_MSG = "On {0}: autostash";
	private static final String REWRITTEN = "rewritten";
	private static final String CURRENT_COMMIT = "current-commit";
	private static final String REFLOG_PREFIX = "rebase:";

	public enum Operation {
		BEGIN,
		CONTINUE,
		SKIP,
		ABORT,
		PROCESS_STEPS
	}

	private Operation operation = Operation.BEGIN;
	private RevCommit upstreamCommit;
	private String upstreamCommitName;
	private ProgressMonitor monitor = NullProgressMonitor.INSTANCE;
	private final RevWalk walk;
	private final RebaseState rebaseState;
	private InteractiveHandler interactiveHandler;
	private CommitConfig commitConfig;
	private RevCommit newHead;
	private boolean lastStepWasForward;
	private MergeStrategy strategy = MergeStrategy.RECURSIVE;
	private ContentMergeStrategy contentStrategy;
	private boolean preserveMerges = false;

	protected RebaseCommand(Repository repo) {
		super(repo);
		walk = new RevWalk(repo);
		rebaseState = new RebaseState(repo.getDirectory());
	}

	@Override
	public RebaseResult call() throws GitAPIException {
		newHead = null;
		lastStepWasForward = false;
		checkCallable();
		checkParameters();
		commitConfig = repo.getConfig().get(CommitConfig.KEY);
		try {
			switch(operation) {
				case ABORT:
					try {
						return abort(RebaseResult.ABORTED_RESULT);
					} catch(IOException ioe) {
						throw new JGitInternalException(ioe.getMessage(), ioe);
					}
				case PROCESS_STEPS:
				case SKIP:
				case CONTINUE:
					String upstreamCommitId = rebaseState.readFile(ONTO);
					try {
						upstreamCommitName = rebaseState.readFile(ONTO_NAME);
					} catch(FileNotFoundException e) {
						upstreamCommitName = upstreamCommitId;
					}
					this.upstreamCommit = walk.parseCommit(repo
							.resolve(upstreamCommitId));
					preserveMerges = rebaseState.getRewrittenDir().isDirectory();
					break;
				case BEGIN:
					autoStash();
					if(!walk.isMergedInto(
							walk.parseCommit(repo.resolve(Constants.HEAD)),
							upstreamCommit)) {
						org.eclipse.jgit.api.Status status = Git.wrap(repo)
								.status().setIgnoreSubmodules(IgnoreSubmoduleMode.ALL).call();
						if(status.hasUncommittedChanges()) {
							return RebaseResult.uncommittedChanges();
						}
					}
					RebaseResult res = initFilesAndRewind();
					if(res != null) {
						autoStashApply();
						if(rebaseState.getDir().exists())
							FileUtils.delete(rebaseState.getDir(),
									FileUtils.RECURSIVE);
						return res;
					}
			}

			if(monitor.isCancelled())
				return abort(RebaseResult.ABORTED_RESULT);

			if(operation == Operation.CONTINUE) {
				newHead = continueRebase();
				List<RebaseTodoLine> doneLines = repo.readRebaseTodo(
						rebaseState.getPath(DONE), true);
				RebaseTodoLine step = doneLines.get(doneLines.size() - 1);
				if(newHead != null
						&& step.getAction() != Action.PICK) {
					RebaseTodoLine newStep = new RebaseTodoLine(
							step.getAction(),
							AbbreviatedObjectId.fromObjectId(newHead),
							step.getShortMessage());
					RebaseResult result = processStep(newStep, false);
					if(result != null)
						return result;
				}
				File amendFile = rebaseState.getFile(AMEND);
				boolean amendExists = amendFile.exists();
				if(amendExists) {
					FileUtils.delete(amendFile);
				}
				if(newHead == null && !amendExists) {
					return RebaseResult.NOTHING_TO_COMMIT_RESULT;
				}
			}

			if(operation == Operation.SKIP)
				newHead = checkoutCurrentHead();

			List<RebaseTodoLine> steps = repo.readRebaseTodo(
					rebaseState.getPath(GIT_REBASE_TODO), false);
			if(steps.isEmpty()) {
				return finishRebase(walk.parseCommit(repo.resolve(Constants.HEAD)), false);
			}
			if(isInteractive()) {
				interactiveHandler.prepareSteps(steps);
				repo.writeRebaseTodoFile(rebaseState.getPath(GIT_REBASE_TODO),
						steps, false);
			}
			checkSteps(steps);
			for(RebaseTodoLine step : steps) {
				popSteps();
				RebaseResult result = processStep(step, true);
				if(result != null) {
					return result;
				}
			}
			return finishRebase(newHead, lastStepWasForward);
		} catch(CheckoutConflictException cce) {
			return RebaseResult.conflicts();
		} catch(IOException ioe) {
			throw new JGitInternalException(ioe.getMessage(), ioe);
		}
	}

	private void autoStash() throws GitAPIException, IOException {
		if(repo.getConfig().getBoolean(ConfigConstants.CONFIG_REBASE_SECTION,
				ConfigConstants.CONFIG_KEY_AUTOSTASH, false)) {
			String message = MessageFormat.format(
					AUTOSTASH_MSG,
					Repository
							.shortenRefName(getHeadName(getHead())));
			RevCommit stashCommit = Git.wrap(repo).stashCreate().setRef(null)
					.setWorkingDirectoryMessage(
							message)
					.call();
			if(stashCommit != null) {
				FileUtils.mkdir(rebaseState.getDir());
				rebaseState.createFile(AUTOSTASH, stashCommit.getName());
			}
		}
	}

	private boolean autoStashApply() throws IOException, GitAPIException {
		boolean conflicts = false;
		if(rebaseState.getFile(AUTOSTASH).exists()) {
			String stash = rebaseState.readFile(AUTOSTASH);
			try(Git git = Git.wrap(repo)) {
				git.stashApply().setStashRef(stash)
						.ignoreRepositoryState(true).setStrategy(strategy).call();
			} catch(StashApplyFailureException e) {
				conflicts = true;
				try(RevWalk rw = new RevWalk(repo)) {
					ObjectId stashId = repo.resolve(stash);
					RevCommit commit = rw.parseCommit(stashId);
					updateStashRef(commit, commit.getAuthorIdent(),
							commit.getShortMessage());
				}
			}
		}
		return conflicts;
	}

	private void updateStashRef(ObjectId commitId, PersonIdent refLogIdent,
								String refLogMessage) throws IOException {
		Ref currentRef = repo.exactRef(Constants.R_STASH);
		RefUpdate refUpdate = repo.updateRef(Constants.R_STASH);
		refUpdate.setNewObjectId(commitId);
		refUpdate.setRefLogIdent(refLogIdent);
		refUpdate.setRefLogMessage(refLogMessage, false);
		refUpdate.setForceRefLog(true);
		if(currentRef != null) refUpdate.setExpectedOldObjectId(currentRef.getObjectId());
		else refUpdate.setExpectedOldObjectId(ObjectId.zeroId());
		refUpdate.forceUpdate();
	}

	private RebaseResult processStep(RebaseTodoLine step, boolean shouldPick)
			throws IOException, GitAPIException {
		if(Action.COMMENT.equals(step.getAction()))
			return null;
		if(preserveMerges
				&& shouldPick
				&& (Action.EDIT.equals(step.getAction()) || Action.PICK
				.equals(step.getAction()))) {
			writeRewrittenHashes();
		}
		ObjectReader or = repo.newObjectReader();

		Collection<ObjectId> ids = or.resolve(step.getCommit());
		if(ids.size() != 1)
			throw new JGitInternalException(JGitText.get().cannotResolveUniquelyAbbrevObjectId);
		RevCommit commitToPick = walk.parseCommit(ids.iterator().next());
		if(shouldPick) {
			if(monitor.isCancelled())
				return RebaseResult.result(Status.STOPPED);
			RebaseResult result = cherryPickCommit(commitToPick);
			if(result != null)
				return result;
		}
		boolean isSquash = false;
		switch(step.getAction()) {
			case PICK:
				return null;
			case REWORD:
				String oldMessage = commitToPick.getFullMessage();
				CleanupMode mode = commitConfig.resolve(CleanupMode.DEFAULT, true);
				boolean[] doChangeId = {false};
				String newMessage = editCommitMessage(doChangeId, oldMessage, mode,
						commitConfig.getCommentChar(oldMessage));
				try(Git git = new Git(repo)) {
					newHead = git.commit()
							.setMessage(newMessage)
							.setAmend(true)
							.setNoVerify(true)
							.setInsertChangeId(doChangeId[0])
							.call();
				}
				return null;
			case EDIT:
				rebaseState.createFile(AMEND, commitToPick.name());
				return stop(commitToPick, Status.EDIT);
			case COMMENT:
				break;
			case SQUASH:
				isSquash = true;
			case FIXUP:
				resetSoftToParent();
				List<RebaseTodoLine> steps = repo.readRebaseTodo(
						rebaseState.getPath(GIT_REBASE_TODO), false);
				boolean isLast = steps.isEmpty();
				if(!isLast) {
					switch(steps.get(0).getAction()) {
						case FIXUP:
						case SQUASH:
							break;
						default:
							isLast = true;
							break;
					}
				}
				File messageFixupFile = rebaseState.getFile(MESSAGE_FIXUP);
				File messageSquashFile = rebaseState.getFile(MESSAGE_SQUASH);
				if(isSquash && messageFixupFile.exists()) {
					messageFixupFile.delete();
				}
				newHead = doSquashFixup(isSquash, commitToPick, isLast,
						messageFixupFile, messageSquashFile);
		}
		return null;
	}

	private String editCommitMessage(boolean[] doChangeId, String message,
									 @NonNull CleanupMode mode, char commentChar) {
		String newMessage;
		CommitConfig.CleanupMode cleanup;
		if(interactiveHandler instanceof InteractiveHandler2) {
			InteractiveHandler2.ModifyResult modification = ((InteractiveHandler2) interactiveHandler)
					.editCommitMessage(message, mode, commentChar);
			newMessage = modification.getMessage();
			cleanup = modification.getCleanupMode();
			if(CleanupMode.DEFAULT.equals(cleanup)) {
				cleanup = mode;
			}
			doChangeId[0] = modification.shouldAddChangeId();
		} else {
			newMessage = interactiveHandler.modifyCommitMessage(message);
			cleanup = CommitConfig.CleanupMode.STRIP;
			doChangeId[0] = false;
		}
		return CommitConfig.cleanText(newMessage, cleanup, commentChar);
	}

	private RebaseResult cherryPickCommit(RevCommit commitToPick)
			throws IOException, GitAPIException {
		try {
			monitor.beginTask(MessageFormat.format(
					JGitText.get().applyingCommit,
					commitToPick.getShortMessage()), ProgressMonitor.UNKNOWN);
			if(preserveMerges) {
				return cherryPickCommitPreservingMerges(commitToPick);
			}
			return cherryPickCommitFlattening(commitToPick);
		} finally {
			monitor.endTask();
		}
	}

	private RebaseResult cherryPickCommitFlattening(RevCommit commitToPick) throws IOException, GitAPIException {
		newHead = tryFastForward(commitToPick);
		lastStepWasForward = newHead != null;
		if(!lastStepWasForward) {
			String ourCommitName = getOurCommitName();
			try(Git git = new Git(repo)) {
				CherryPickResult cherryPickResult = git.cherryPick()
						.include(commitToPick).setOurCommitName(ourCommitName)
						.setReflogPrefix(REFLOG_PREFIX).setStrategy(strategy)
						.setContentMergeStrategy(contentStrategy).call();
				switch(cherryPickResult.getStatus()) {
					case FAILED:
						if(operation == Operation.BEGIN) {
							return abort(RebaseResult
									.failed());
						}
						return stop(commitToPick, Status.STOPPED);
					case CONFLICTING:
						return stop(commitToPick, Status.STOPPED);
					case OK:
						newHead = cherryPickResult.getNewHead();
				}
			}
		}
		return null;
	}

	private RebaseResult cherryPickCommitPreservingMerges(RevCommit commitToPick)
			throws IOException, GitAPIException {

		writeCurrentCommit(commitToPick);

		List<RevCommit> newParents = getNewParents(commitToPick);
		boolean otherParentsUnchanged = true;
		for(int i = 1; i < commitToPick.getParentCount(); i++)
			otherParentsUnchanged &= newParents.get(i).equals(
					commitToPick.getParent(i));
		newHead = otherParentsUnchanged ? tryFastForward(commitToPick) : null;
		lastStepWasForward = newHead != null;
		if(!lastStepWasForward) {
			ObjectId headId = getHead().getObjectId();
			assert headId != null;
			if(!AnyObjectId.isEqual(headId, newParents.get(0)))
				checkoutCommit(headId.getName(), newParents.get(0));

			try(Git git = new Git(repo)) {
				if(otherParentsUnchanged) {
					boolean isMerge = commitToPick.getParentCount() > 1;
					String ourCommitName = getOurCommitName();
					CherryPickCommand pickCommand = git.cherryPick()
							.include(commitToPick)
							.setOurCommitName(ourCommitName)
							.setReflogPrefix(REFLOG_PREFIX)
							.setStrategy(strategy)
							.setContentMergeStrategy(contentStrategy);
					if(isMerge) {
						pickCommand.setMainlineParentNumber(1);
						pickCommand.setNoCommit(true);
						writeMergeInfo(commitToPick, newParents);
					}
					CherryPickResult cherryPickResult = pickCommand.call();
					switch(cherryPickResult.getStatus()) {
						case FAILED:
							if(operation == Operation.BEGIN) {
								return abort(RebaseResult.failed(
								));
							}
							return stop(commitToPick, Status.STOPPED);
						case CONFLICTING:
							return stop(commitToPick, Status.STOPPED);
						case OK:
							if(isMerge) {
								CommitCommand commit = git.commit();
								commit.setAuthor(commitToPick.getAuthorIdent());
								commit.setReflogComment(REFLOG_PREFIX + " "
										+ commitToPick.getShortMessage());
								newHead = commit.call();
							} else
								newHead = cherryPickResult.getNewHead();
							break;
					}
				} else {
					MergeCommand merge = git.merge().setFastForward(MergeCommand.FastForwardMode.NO_FF)
							.setProgressMonitor(monitor).setStrategy(strategy)
							.setContentMergeStrategy(contentStrategy).setCommit(false);
					for(int i = 1; i < commitToPick.getParentCount(); i++)
						merge.include(newParents.get(i));
					MergeResult mergeResult = merge.call();
					if(mergeResult.getMergeStatus().isSuccessful()) {
						CommitCommand commit = git.commit();
						commit.setAuthor(commitToPick.getAuthorIdent());
						commit.setMessage(commitToPick.getFullMessage());
						commit.setReflogComment(REFLOG_PREFIX + " " + commitToPick.getShortMessage());
						newHead = commit.call();
					} else {
						if(operation == Operation.BEGIN && mergeResult.getMergeStatus() == MergeResult.MergeStatus.FAILED)
							return abort(RebaseResult.failed());
						return stop(commitToPick, Status.STOPPED);
					}
				}
			}
		}
		return null;
	}

	private void writeMergeInfo(RevCommit commitToPick,
								List<RevCommit> newParents) throws IOException {
		repo.writeMergeHeads(newParents.subList(1, newParents.size()));
		repo.writeMergeCommitMsg(commitToPick.getFullMessage());
	}

	private List<RevCommit> getNewParents(RevCommit commitToPick)
			throws IOException {
		List<RevCommit> newParents = new ArrayList<>();
		for(int p = 0; p < commitToPick.getParentCount(); p++) {
			String parentHash = commitToPick.getParent(p).getName();
			if(!new File(rebaseState.getRewrittenDir(), parentHash).exists())
				newParents.add(commitToPick.getParent(p));
			else {
				String newParent = RebaseState.readFile(rebaseState.getRewrittenDir(), parentHash);
				if(newParent.length() == 0)
					newParents.add(walk.parseCommit(repo.resolve(Constants.HEAD)));
				else
					newParents.add(walk.parseCommit(ObjectId.fromString(newParent)));
			}
		}
		return newParents;
	}

	private void writeCurrentCommit(RevCommit commit) throws IOException {
		RebaseState.appendToFile(rebaseState.getFile(CURRENT_COMMIT), commit.name());
	}

	private void writeRewrittenHashes() throws RevisionSyntaxException, IOException, RefNotFoundException {
		File currentCommitFile = rebaseState.getFile(CURRENT_COMMIT);
		if(!currentCommitFile.exists())
			return;

		ObjectId headId = getHead().getObjectId();
		assert headId != null;
		String head = headId.getName();
		String currentCommits = rebaseState.readFile(CURRENT_COMMIT);
		for(String current : currentCommits.split("\n"))
			RebaseState.createFile(rebaseState.getRewrittenDir(), current, head);
		FileUtils.delete(currentCommitFile);
	}

	private RebaseResult finishRebase(RevCommit finalHead,
									  boolean lastStepIsForward) throws IOException, GitAPIException {
		String headName = rebaseState.readFile(HEAD_NAME);
		updateHead(headName, finalHead, upstreamCommit);
		boolean stashConflicts = autoStashApply();
		getRepository().autoGC(monitor);
		FileUtils.delete(rebaseState.getDir(), FileUtils.RECURSIVE);
		if(stashConflicts) return RebaseResult.STASH_APPLY_CONFLICTS_RESULT;
		if(lastStepIsForward || finalHead == null) return RebaseResult.FAST_FORWARD_RESULT;
		return RebaseResult.OK_RESULT;
	}

	private void checkSteps(List<RebaseTodoLine> steps) throws InvalidRebaseStepException, IOException {
		if(steps.isEmpty()) return;
		if(RebaseTodoLine.Action.SQUASH.equals(steps.get(0).getAction())
				|| RebaseTodoLine.Action.FIXUP.equals(steps.get(0).getAction())) {
			if(!rebaseState.getFile(DONE).exists()
					|| rebaseState.readFile(DONE).trim().length() == 0) {
				throw new InvalidRebaseStepException(MessageFormat.format(
						JGitText.get().cannotSquashFixupWithoutPreviousCommit, steps.get(0).getAction().name()));
			}
		}

	}

	private RevCommit doSquashFixup(boolean isSquash, RevCommit commitToPick,
									boolean isLast, File messageFixup, File messageSquash) throws IOException, GitAPIException {

		if(!messageSquash.exists()) {
			ObjectId headId = repo.resolve(Constants.HEAD);
			RevCommit previousCommit = walk.parseCommit(headId);

			initializeSquashFixupFile(previousCommit.getFullMessage());
			if(!isSquash) {
				rebaseState.createFile(MESSAGE_FIXUP, previousCommit.getFullMessage());
			}
		}

		String currSquashMessage = rebaseState.readFile(MESSAGE_SQUASH);
		int count = parseSquashFixupSequenceCount(currSquashMessage) + 1;
		String content = composeSquashMessage(isSquash, commitToPick, currSquashMessage, count);
		rebaseState.createFile(MESSAGE_SQUASH, content);

		return squashIntoPrevious(!messageFixup.exists(), isLast);
	}

	private void resetSoftToParent() throws IOException,
			GitAPIException {
		Ref ref = repo.exactRef(Constants.ORIG_HEAD);
		ObjectId orig_head = ref == null ? null : ref.getObjectId();
		try(Git git = Git.wrap(repo)) {
			git.reset().setMode(ResetType.SOFT)
					.setRef("HEAD~1").call();
		} finally {
			repo.writeOrigHead(orig_head);
		}
	}

	private RevCommit squashIntoPrevious(boolean sequenceContainsSquash,
										 boolean isLast)
			throws IOException, GitAPIException {
		RevCommit retNewHead;
		String commitMessage;
		if(!isLast || sequenceContainsSquash) {
			commitMessage = rebaseState.readFile(MESSAGE_SQUASH);
		} else {
			commitMessage = rebaseState.readFile(MESSAGE_FIXUP);
		}
		try(Git git = new Git(repo)) {
			if(isLast) {
				boolean[] doChangeId = {false};
				if(sequenceContainsSquash) {
					char commentChar = commitMessage.charAt(0);
					commitMessage = editCommitMessage(doChangeId, commitMessage,
							CleanupMode.STRIP, commentChar);
				}
				retNewHead = git.commit().setMessage(commitMessage)
						.setAmend(true).setNoVerify(true)
						.setInsertChangeId(doChangeId[0]).call();
				rebaseState.getFile(MESSAGE_SQUASH).delete();
				rebaseState.getFile(MESSAGE_FIXUP).delete();
			} else {
				retNewHead = git.commit().setMessage(commitMessage)
						.setAmend(true).setNoVerify(true).call();
			}
		}
		return retNewHead;
	}

	@SuppressWarnings("nls")
	private String composeSquashMessage(boolean isSquash,
										RevCommit commitToPick, String currSquashMessage, int count) {
		StringBuilder sb = new StringBuilder();
		String ordinal = getOrdinal(count);
		char commentChar = currSquashMessage.charAt(0);
		String newMessage = commitToPick.getFullMessage();
		if(!isSquash) {
			sb.append(commentChar).append(" This is a combination of ").append(count).append(" commits.\n");
			sb.append(currSquashMessage.substring(currSquashMessage.indexOf('\n') + 1));
			sb.append('\n');
			sb.append(commentChar).append(" The ").append(count).append(ordinal)
					.append(" commit message will be skipped:\n").append(commentChar).append(' ');
			sb.append(newMessage.replaceAll("([\n\r])",
					"$1" + commentChar + ' '));
		} else {
			String currentMessage = currSquashMessage;
			if(commitConfig.isAutoCommentChar()) {
				String cleaned = CommitConfig.cleanText(currentMessage,
						CommitConfig.CleanupMode.STRIP, commentChar) + '\n' + newMessage;
				char newCommentChar = commitConfig.getCommentChar(cleaned);
				if(newCommentChar != commentChar) {
					currentMessage = replaceCommentChar(currentMessage, commentChar, newCommentChar);
					commentChar = newCommentChar;
				}
			}
			sb.append(commentChar).append(" This is a combination of ").append(count).append(" commits.\n");
			sb.append(currentMessage.substring(currentMessage.indexOf('\n') + 1)).append('\n');
			sb.append(commentChar).append(" This is the ").append(count).append(ordinal).append(" commit message:\n");
			sb.append(newMessage);
		}
		return sb.toString();
	}

	private String replaceCommentChar(String message, char oldChar, char newChar) {
		return message.replaceAll("(?m)^(\\h*)" + oldChar, "$1" + newChar);
	}

	private static String getOrdinal(int count) {
		switch(count % 10) {
			case 1:
				return "st";
			case 2:
				return "nd";
			case 3:
				return "rd";
			default:
				return "th";
		}
	}

	static int parseSquashFixupSequenceCount(String currSquashMessage) {
		String regex = "This is a combination of (.*) commits";
		String firstLine = currSquashMessage.substring(0,
				currSquashMessage.indexOf('\n'));
		Pattern pattern = Pattern.compile(regex);
		Matcher matcher = pattern.matcher(firstLine);
		if(!matcher.find())
			throw new IllegalArgumentException();
		return Integer.parseInt(matcher.group(1));
	}

	private void initializeSquashFixupFile(String fullMessage) throws IOException {
		char commentChar = commitConfig.getCommentChar(fullMessage);
		rebaseState.createFile(RebaseCommand.MESSAGE_SQUASH,
				commentChar + " This is a combination of 1 commits.\n"
						+ commentChar + " The first commit's message is:\n"
						+ fullMessage);
	}

	private String getOurCommitName() {
		return "Upstream, based on " + Repository.shortenRefName(upstreamCommitName);
	}

	private void updateHead(String headName, RevCommit aNewHead, RevCommit onto) throws IOException {

		if(headName.startsWith(Constants.R_REFS)) {
			RefUpdate rup = repo.updateRef(headName);
			rup.setNewObjectId(aNewHead);
			rup.setRefLogMessage("rebase finished: " + headName + " onto "
					+ onto.getName(), false);
			Result res = rup.forceUpdate();
			switch(res) {
				case FAST_FORWARD:
				case FORCED:
				case NO_CHANGE:
					break;
				default:
					throw new JGitInternalException(
							JGitText.get().updatingHeadFailed);
			}
			rup = repo.updateRef(Constants.HEAD);
			rup.setRefLogMessage("rebase finished: returning to " + headName,
					false);
			res = rup.link(headName);
			switch(res) {
				case FAST_FORWARD:
				case FORCED:
				case NO_CHANGE:
					break;
				default:
					throw new JGitInternalException(
							JGitText.get().updatingHeadFailed);
			}
		}
	}

	private RevCommit checkoutCurrentHead() throws IOException, NoHeadException {
		ObjectId headTree = repo.resolve(Constants.HEAD + "^{tree}");
		if(headTree == null)
			throw new NoHeadException(
					JGitText.get().cannotRebaseWithoutCurrentHead);
		DirCache dc = repo.lockDirCache();
		try {
			DirCacheCheckout dco = new DirCacheCheckout(repo, dc, headTree);
			dco.setFailOnConflict(false);
			dco.setProgressMonitor(monitor);
			boolean needsDeleteFiles = dco.checkout();
			if(needsDeleteFiles) {
				List<String> fileList = dco.getToBeDeleted();
				for(String filePath : fileList) {
					File fileToDelete = new File(repo.getWorkTree(), filePath);
					if(repo.getFS().exists(fileToDelete))
						FileUtils.delete(fileToDelete, FileUtils.RECURSIVE
								| FileUtils.RETRY);
				}
			}
		} finally {
			dc.unlock();
		}
		try(RevWalk rw = new RevWalk(repo)) {
			return rw.parseCommit(repo.resolve(Constants.HEAD));
		}
	}

	private RevCommit continueRebase() throws GitAPIException, IOException {
		DirCache dc = repo.readDirCache();
		boolean hasUnmergedPaths = dc.hasUnmergedPaths();
		if(hasUnmergedPaths) throw new UnmergedPathsException();

		boolean needsCommit;
		try(TreeWalk treeWalk = new TreeWalk(repo)) {
			treeWalk.reset();
			treeWalk.setRecursive(true);
			treeWalk.addTree(new DirCacheIterator(dc));
			ObjectId id = repo.resolve(Constants.HEAD + "^{tree}");
			if(id == null)
				throw new NoHeadException(JGitText.get().cannotRebaseWithoutCurrentHead);

			treeWalk.addTree(id);
			treeWalk.setFilter(TreeFilter.ANY_DIFF);
			needsCommit = treeWalk.next();
		}
		if(needsCommit) {
			try(Git git = new Git(repo)) {
				CommitCommand commit = git.commit();
				commit.setMessage(rebaseState.readFile(MESSAGE));
				commit.setAuthor(parseAuthor());
				return commit.call();
			}
		}
		return null;
	}

	private PersonIdent parseAuthor() throws IOException {
		File authorScriptFile = rebaseState.getFile(AUTHOR_SCRIPT);
		byte[] raw;
		try {
			raw = IO.readFully(authorScriptFile);
		} catch(FileNotFoundException notFound) {
			if(authorScriptFile.exists()) {
				throw notFound;
			}
			return null;
		}
		return parseAuthor(raw);
	}

	private RebaseResult stop(RevCommit commitToPick, RebaseResult.Status status)
			throws IOException {
		PersonIdent author = commitToPick.getAuthorIdent();
		String authorScript = toAuthorScript(author);
		rebaseState.createFile(AUTHOR_SCRIPT, authorScript);
		rebaseState.createFile(MESSAGE, commitToPick.getFullMessage());
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		try(DiffFormatter df = new DiffFormatter(bos)) {
			df.setRepository(repo);
			df.format(commitToPick.getParent(0), commitToPick);
		}
		rebaseState.createFile(PATCH, new String(bos.toByteArray(), UTF_8));
		rebaseState.createFile(STOPPED_SHA,
				repo.newObjectReader()
						.abbreviate(
								commitToPick).name());
		repo.writeCherryPickHead(null);
		return RebaseResult.result(status);
	}

	String toAuthorScript(PersonIdent author) {
		StringBuilder sb = new StringBuilder(100);
		sb.append(GIT_AUTHOR_NAME);
		sb.append("='");
		sb.append(author.getName()).append("'\n");
		sb.append(GIT_AUTHOR_EMAIL);
		sb.append("='");
		sb.append(author.getEmailAddress()).append("'\n");
		sb.append(GIT_AUTHOR_DATE);
		sb.append("='");
		sb.append("@");
		String externalString = author.toExternalString();
		sb.append(externalString.substring(externalString.lastIndexOf('>') + 2));
		sb.append("'\n");
		return sb.toString();
	}

	private void popSteps() throws IOException {
		List<RebaseTodoLine> todoLines = new LinkedList<>();
		List<RebaseTodoLine> poppedLines = new LinkedList<>();

		for(RebaseTodoLine line : repo.readRebaseTodo(rebaseState.getPath(GIT_REBASE_TODO), true)) {
			if(poppedLines.size() >= 1 || RebaseTodoLine.Action.COMMENT.equals(line.getAction()))
				todoLines.add(line);
			else
				poppedLines.add(line);
		}

		repo.writeRebaseTodoFile(rebaseState.getPath(GIT_REBASE_TODO),
				todoLines, false);
		if(!poppedLines.isEmpty()) {
			repo.writeRebaseTodoFile(rebaseState.getPath(DONE), poppedLines,
					true);
		}
	}

	private RebaseResult initFilesAndRewind() throws IOException, GitAPIException {

		Ref head = getHead();
		ObjectId headId = head.getObjectId();
		if(headId == null) {
			throw new RefNotFoundException(MessageFormat.format(JGitText.get().refNotResolved, Constants.HEAD));
		}
		String headName = getHeadName(head);
		RevCommit headCommit = walk.lookupCommit(headId);
		RevCommit upstream = walk.lookupCommit(upstreamCommit.getId());

		if(!isInteractive() && walk.isMergedInto(upstream, headCommit))
			return RebaseResult.UP_TO_DATE_RESULT;
		else if(!isInteractive() && walk.isMergedInto(headCommit, upstream)) {
			monitor.beginTask(MessageFormat.format(JGitText.get().resettingHead,
					upstreamCommit.getShortMessage()), ProgressMonitor.UNKNOWN);
			checkoutCommit(headName, upstreamCommit);
			monitor.endTask();

			updateHead(headName, upstreamCommit, upstream);
			return RebaseResult.FAST_FORWARD_RESULT;
		}

		monitor.beginTask(JGitText.get().obtainingCommitsForCherryPick, ProgressMonitor.UNKNOWN);

		FileUtils.mkdir(rebaseState.getDir(), true);

		repo.writeOrigHead(headId);
		rebaseState.createFile(REBASE_HEAD, headId.name());
		rebaseState.createFile(REBASE_HEAD_LEGACY, headId.name());
		rebaseState.createFile(HEAD_NAME, headName);
		rebaseState.createFile(ONTO, upstreamCommit.name());
		rebaseState.createFile(ONTO_NAME, upstreamCommitName);
		if(isInteractive() || preserveMerges) {
			rebaseState.createFile(INTERACTIVE, "");
		}
		rebaseState.createFile(QUIET, "");

		ArrayList<RebaseTodoLine> toDoSteps = new ArrayList<>();
		toDoSteps.add(new RebaseTodoLine("# Created by EGit: rebasing " + headId.name()
				+ " onto " + upstreamCommit.name()));
		List<RevCommit> cherryPickList = calculatePickList(headCommit);
		ObjectReader reader = walk.getObjectReader();
		for(RevCommit commit : cherryPickList)
			toDoSteps.add(new RebaseTodoLine(Action.PICK, reader.abbreviate(commit), commit.getShortMessage()));
		repo.writeRebaseTodoFile(rebaseState.getPath(GIT_REBASE_TODO), toDoSteps, false);

		monitor.endTask();

		monitor.beginTask(MessageFormat.format(JGitText.get().rewinding,
				upstreamCommit.getShortMessage()), ProgressMonitor.UNKNOWN);
		boolean checkoutOk = false;
		try {
			checkoutOk = checkoutCommit(headName, upstreamCommit);
		} finally {
			if(!checkoutOk)
				FileUtils.delete(rebaseState.getDir(), FileUtils.RECURSIVE);
		}
		monitor.endTask();

		return null;
	}

	private List<RevCommit> calculatePickList(RevCommit headCommit)
			throws IOException {
		List<RevCommit> cherryPickList = new ArrayList<>();
		try(RevWalk r = new RevWalk(repo)) {
			r.sort(RevSort.TOPO_KEEP_BRANCH_TOGETHER, true);
			r.sort(RevSort.COMMIT_TIME_DESC, true);
			r.markUninteresting(r.lookupCommit(upstreamCommit));
			r.markStart(r.lookupCommit(headCommit));
			for(RevCommit commit : r) {
				if(preserveMerges || commit.getParentCount() == 1) {
					cherryPickList.add(commit);
				}
			}
		}
		Collections.reverse(cherryPickList);

		if(preserveMerges) {
			File rewrittenDir = rebaseState.getRewrittenDir();
			FileUtils.mkdir(rewrittenDir, false);
			walk.reset();
			walk.setRevFilter(RevFilter.MERGE_BASE);
			walk.markStart(upstreamCommit);
			walk.markStart(headCommit);
			RevCommit base;
			while((base = walk.next()) != null)
				RebaseState.createFile(rewrittenDir, base.getName(),
						upstreamCommit.getName());

			Iterator<RevCommit> iterator = cherryPickList.iterator();
			pickLoop:
			while(iterator.hasNext()) {
				RevCommit commit = iterator.next();
				for(int i = 0; i < commit.getParentCount(); i++) {
					boolean parentRewritten = new File(rewrittenDir, commit
							.getParent(i).getName()).exists();
					if(parentRewritten) {
						new File(rewrittenDir, commit.getName()).createNewFile();
						continue pickLoop;
					}
				}
				iterator.remove();
			}
		}
		return cherryPickList;
	}

	private static String getHeadName(Ref head) {
		String headName;
		if(head.isSymbolic()) {
			headName = head.getTarget().getName();
		} else {
			ObjectId headId = head.getObjectId();
			assert headId != null;
			headName = headId.getName();
		}
		return headName;
	}

	private Ref getHead() throws IOException, RefNotFoundException {
		Ref head = repo.exactRef(Constants.HEAD);
		if(head == null || head.getObjectId() == null)
			throw new RefNotFoundException(MessageFormat.format(
					JGitText.get().refNotResolved, Constants.HEAD));
		return head;
	}

	private boolean isInteractive() {
		return interactiveHandler != null;
	}

	public RevCommit tryFastForward(RevCommit newCommit) throws IOException, GitAPIException {
		Ref head = getHead();

		ObjectId headId = head.getObjectId();
		if(headId == null)
			throw new RefNotFoundException(MessageFormat.format(JGitText.get().refNotResolved, Constants.HEAD));
		RevCommit headCommit = walk.lookupCommit(headId);
		if(walk.isMergedInto(newCommit, headCommit)) return newCommit;

		String headName = getHeadName(head);
		return tryFastForward(headName, headCommit, newCommit);
	}

	private RevCommit tryFastForward(String headName, RevCommit oldCommit,
									 RevCommit newCommit) throws IOException, GitAPIException {
		boolean tryRebase = false;
		for(RevCommit parentCommit : newCommit.getParents())
			if(parentCommit.equals(oldCommit)) {
				tryRebase = true;
				break;
			}
		if(!tryRebase) return null;

		CheckoutCommand co = new CheckoutCommand(repo);
		try {
			co.setProgressMonitor(monitor);
			co.setName(newCommit.name()).call();
			if(headName.startsWith(Constants.R_HEADS)) {
				RefUpdate rup = repo.updateRef(headName);
				rup.setExpectedOldObjectId(oldCommit);
				rup.setNewObjectId(newCommit);
				rup.setRefLogMessage("Fast-forward from " + oldCommit.name()
						+ " to " + newCommit.name(), false);
				Result res = rup.update(walk);
				switch(res) {
					case FAST_FORWARD:
					case NO_CHANGE:
					case FORCED:
						break;
					default:
						throw new IOException("Could not fast-forward");
				}
			}
			return newCommit;
		} catch(RefAlreadyExistsException | RefNotFoundException
				| InvalidRefNameException | CheckoutConflictException e) {
			throw new JGitInternalException(e.getMessage(), e);
		}
	}

	private void checkParameters() throws WrongRepositoryStateException {
		if(this.operation == Operation.PROCESS_STEPS) {
			if(rebaseState.getFile(DONE).exists())
				throw new WrongRepositoryStateException(MessageFormat.format(
						JGitText.get().wrongRepositoryState, repo
								.getRepositoryState().name()));
		}
		if(this.operation != Operation.BEGIN) {
			switch(repo.getRepositoryState()) {
				case REBASING_INTERACTIVE:
				case REBASING:
				case REBASING_REBASING:
				case REBASING_MERGE:
					break;
				default:
					throw new WrongRepositoryStateException(MessageFormat.format(
							JGitText.get().wrongRepositoryState, repo
									.getRepositoryState().name()));
			}
		} else if(repo.getRepositoryState() == RepositoryState.SAFE) {
			if(this.upstreamCommit == null)
				throw new JGitInternalException(MessageFormat
						.format(JGitText.get().missingRequiredParameter,
								"upstream"));
		} else {
			throw new WrongRepositoryStateException(MessageFormat.format(
					JGitText.get().wrongRepositoryState, repo
							.getRepositoryState().name()));
		}
	}

	private RebaseResult abort(RebaseResult result) throws IOException,
			GitAPIException {
		ObjectId origHead = getOriginalHead();
		try {
			String commitId = origHead != null ? origHead.name() : null;
			monitor.beginTask(MessageFormat.format(
							JGitText.get().abortingRebase, commitId),
					ProgressMonitor.UNKNOWN);

			DirCacheCheckout dco;
			if(commitId == null)
				throw new JGitInternalException(
						JGitText.get().abortingRebaseFailedNoOrigHead);
			ObjectId id = repo.resolve(commitId);
			RevCommit commit = walk.parseCommit(id);
			if(result.getStatus().equals(Status.FAILED)) {
				RevCommit head = walk.parseCommit(repo.resolve(Constants.HEAD));
				dco = new DirCacheCheckout(repo, head.getTree(),
						repo.lockDirCache(), commit.getTree());
			} else {
				dco = new DirCacheCheckout(repo, repo.lockDirCache(),
						commit.getTree());
			}
			dco.setFailOnConflict(false);
			dco.checkout();
			walk.close();
		} finally {
			monitor.endTask();
		}
		try {
			String headName = rebaseState.readFile(HEAD_NAME);
			monitor.beginTask(MessageFormat.format(
							JGitText.get().resettingHead, headName),
					ProgressMonitor.UNKNOWN);

			Result res;
			RefUpdate refUpdate = repo.updateRef(Constants.HEAD, false);
			refUpdate.setRefLogMessage("rebase: aborting", false);
			if(headName.startsWith(Constants.R_REFS)) {
				res = refUpdate.link(headName);
			} else {
				refUpdate.setNewObjectId(origHead);
				res = refUpdate.forceUpdate();

			}
			switch(res) {
				case FAST_FORWARD:
				case FORCED:
				case NO_CHANGE:
					break;
				default:
					throw new JGitInternalException(
							JGitText.get().abortingRebaseFailed);
			}
			boolean stashConflicts = autoStashApply();
			FileUtils.delete(rebaseState.getDir(), FileUtils.RECURSIVE);
			repo.writeCherryPickHead(null);
			repo.writeMergeHeads(null);
			if(stashConflicts)
				return RebaseResult.STASH_APPLY_CONFLICTS_RESULT;
			return result;

		} finally {
			monitor.endTask();
		}
	}

	private ObjectId getOriginalHead() throws IOException {
		try {
			return ObjectId.fromString(rebaseState.readFile(REBASE_HEAD));
		} catch(FileNotFoundException e) {
			try {
				return ObjectId
						.fromString(rebaseState.readFile(REBASE_HEAD_LEGACY));
			} catch(FileNotFoundException ex) {
				return repo.readOrigHead();
			}
		}
	}

	private boolean checkoutCommit(String headName, RevCommit commit)
			throws IOException,
			CheckoutConflictException {
		try {
			RevCommit head = walk.parseCommit(repo.resolve(Constants.HEAD));
			DirCacheCheckout dco = new DirCacheCheckout(repo, head.getTree(),
					repo.lockDirCache(), commit.getTree());
			dco.setFailOnConflict(true);
			dco.setProgressMonitor(monitor);
			try {
				dco.checkout();
			} catch(org.eclipse.jgit.errors.CheckoutConflictException cce) {
				throw new CheckoutConflictException(dco.getConflicts(), cce);
			}
			RefUpdate refUpdate = repo.updateRef(Constants.HEAD, true);
			refUpdate.setExpectedOldObjectId(head);
			refUpdate.setNewObjectId(commit);
			refUpdate.setRefLogMessage(
					"checkout: moving from "
							+ Repository.shortenRefName(headName)
							+ " to " + commit.getName(), false);
			Result res = refUpdate.forceUpdate();
			switch(res) {
				case FAST_FORWARD:
				case NO_CHANGE:
				case FORCED:
					break;
				default:
					throw new IOException(
							JGitText.get().couldNotRewindToUpstreamCommit);
			}
		} finally {
			walk.close();
			monitor.endTask();
		}
		return true;
	}

	public RebaseCommand setUpstream(RevCommit upstream) {
		this.upstreamCommit = upstream;
		this.upstreamCommitName = upstream.name();
		return this;
	}

	public RebaseCommand setUpstream(AnyObjectId upstream) {
		try {
			this.upstreamCommit = walk.parseCommit(upstream);
			this.upstreamCommitName = upstream.name();
		} catch(IOException e) {
			throw new JGitInternalException(MessageFormat.format(
					JGitText.get().couldNotReadObjectWhileParsingCommit,
					upstream.name()), e);
		}
		return this;
	}

	public RebaseCommand setUpstreamName(String upstreamName) {
		if(upstreamCommit == null) {
			throw new IllegalStateException(
					"setUpstreamName must be called after setUpstream.");
		}
		this.upstreamCommitName = upstreamName;
		return this;
	}

	public RebaseCommand setOperation(Operation operation) {
		this.operation = operation;
		return this;
	}

	public RebaseCommand setProgressMonitor(ProgressMonitor monitor) {
		if(monitor == null) {
			monitor = NullProgressMonitor.INSTANCE;
		}
		this.monitor = monitor;
		return this;
	}

	public RebaseCommand setStrategy(MergeStrategy strategy) {
		this.strategy = strategy;
		return this;
	}

	public RebaseCommand setContentMergeStrategy(ContentMergeStrategy strategy) {
		this.contentStrategy = strategy;
		return this;
	}

	public RebaseCommand setPreserveMerges(boolean preserve) {
		this.preserveMerges = preserve;
		return this;
	}

	public interface InteractiveHandler {

		void prepareSteps(List<RebaseTodoLine> steps);

		String modifyCommitMessage(String message);
	}

	public interface InteractiveHandler2 extends InteractiveHandler {

		@NonNull
		ModifyResult editCommitMessage(@NonNull String message,
									   @NonNull CleanupMode mode, char commentChar);

		@Override
		default String modifyCommitMessage(String message) {
			ModifyResult result = editCommitMessage(message == null ? "" : message, CleanupMode.STRIP, '#');
			return result.getMessage();
		}

		interface ModifyResult {

			@NonNull
			String getMessage();

			@NonNull
			CleanupMode getCleanupMode();

			boolean shouldAddChangeId();
		}
	}

	PersonIdent parseAuthor(byte[] raw) {
		if(raw.length == 0)
			return null;

		Map<String, String> keyValueMap = new HashMap<>();
		for(int p = 0; p < raw.length; ) {
			int end = RawParseUtils.nextLF(raw, p);
			if(end == p)
				break;
			int equalsIndex = RawParseUtils.next(raw, p, '=');
			if(equalsIndex == end)
				break;
			String key = RawParseUtils.decode(raw, p, equalsIndex - 1);
			String value = RawParseUtils.decode(raw, equalsIndex + 1, end - 2);
			p = end;
			keyValueMap.put(key, value);
		}

		String name = keyValueMap.get(GIT_AUTHOR_NAME);
		String email = keyValueMap.get(GIT_AUTHOR_EMAIL);
		String time = keyValueMap.get(GIT_AUTHOR_DATE);

		int timeStart = 0;
		if(time.startsWith("@"))
			timeStart = 1;
		long when = Long
				.parseLong(time.substring(timeStart, time.indexOf(' '))) * 1000;
		String tzOffsetString = time.substring(time.indexOf(' ') + 1);
		int multiplier = -1;
		if(tzOffsetString.charAt(0) == '+')
			multiplier = 1;
		int hours = Integer.parseInt(tzOffsetString.substring(1, 3));
		int minutes = Integer.parseInt(tzOffsetString.substring(3, 5));
		int tz = (hours * 60 + minutes) * multiplier;
		if(name != null && email != null) return new PersonIdent(name, email, when, tz);
		return null;
	}

	private static class RebaseState {

		private final File repoDirectory;
		private File dir;

		public RebaseState(File repoDirectory) {
			this.repoDirectory = repoDirectory;
		}

		public File getDir() {
			if(dir == null) {
				File rebaseApply = new File(repoDirectory, REBASE_APPLY);
				if(rebaseApply.exists()) {
					dir = rebaseApply;
				} else {
					dir = new File(repoDirectory, REBASE_MERGE);
				}
			}
			return dir;
		}

		public File getRewrittenDir() {
			return new File(getDir(), REWRITTEN);
		}

		public String readFile(String name) throws IOException {
			try {
				return readFile(getDir(), name);
			} catch(FileNotFoundException e) {
				if(ONTO_NAME.equals(name)) {
					File oldFile = getFile(ONTO_NAME.replace('_', '-'));
					if(oldFile.exists()) {
						return readFile(oldFile);
					}
				}
				throw e;
			}
		}

		public void createFile(String name, String content) throws IOException {
			createFile(getDir(), name, content);
		}

		public File getFile(String name) {
			return new File(getDir(), name);
		}

		public String getPath(String name) {
			return (getDir().getName() + "/" + name);
		}

		private static String readFile(File file) throws IOException {
			byte[] content = IO.readFully(file);
			int end = RawParseUtils.prevLF(content, content.length);
			return RawParseUtils.decode(content, 0, end + 1);
		}

		private static String readFile(File directory, String fileName)
				throws IOException {
			return readFile(new File(directory, fileName));
		}

		private static void createFile(File parentDir, String name,
									   String content)
				throws IOException {
			File file = new File(parentDir, name);
			try(FileOutputStream fos = new FileOutputStream(file)) {
				fos.write(content.getBytes(UTF_8));
				fos.write('\n');
			}
		}

		private static void appendToFile(File file, String content)
				throws IOException {
			try(FileOutputStream fos = new FileOutputStream(file, true)) {
				fos.write(content.getBytes(UTF_8));
				fos.write('\n');
			}
		}
	}
}
