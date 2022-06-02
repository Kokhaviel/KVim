/*
 * Copyright (C) 2010, Christian Halstrick <christian.halstrick@sap.com>
 * Copyright (C) 2010, 2014, Stefan Lay <stefan.lay@sap.com>
 * Copyright (C) 2016, 2021 Laurent Delaigue <laurent.delaigue@obeo.fr> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.api;

import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.api.MergeResult.MergeStatus;
import org.eclipse.jgit.api.errors.*;
import org.eclipse.jgit.dircache.DirCacheCheckout;
import org.eclipse.jgit.events.WorkingTreeModifiedEvent;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.lib.Config.ConfigEnum;
import org.eclipse.jgit.lib.Ref.Storage;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.merge.*;
import org.eclipse.jgit.merge.ResolveMerger.MergeFailureReason;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.RevWalkUtils;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.util.StringUtils;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.*;

public class MergeCommand extends GitCommand<MergeResult> {

	private MergeStrategy mergeStrategy = MergeStrategy.RECURSIVE;
	private ContentMergeStrategy contentStrategy;
	private final List<Ref> commits = new LinkedList<>();
	private Boolean squash;
	private FastForwardMode fastForwardMode;
	private String message;
	private boolean insertChangeId;
	private ProgressMonitor monitor = NullProgressMonitor.INSTANCE;

	public enum FastForwardMode implements ConfigEnum {
		FF,
		NO_FF,
		FF_ONLY;

		@Override
		public String toConfigValue() {
			return "--" + name().toLowerCase(Locale.ROOT).replace('_', '-');
		}

		@Override
		public boolean matchConfigValue(String in) {
			if(StringUtils.isEmptyOrNull(in))
				return false;
			if(!in.startsWith("--"))
				return false;
			return name().equalsIgnoreCase(in.substring(2).replace('-', '_'));
		}

		public enum Merge {
			TRUE,
			FALSE,
			ONLY;

			public static Merge valueOf(FastForwardMode ffMode) {
				switch(ffMode) {
					case NO_FF:
						return FALSE;
					case FF_ONLY:
						return ONLY;
					default:
						return TRUE;
				}
			}
		}

		public static FastForwardMode valueOf(FastForwardMode.Merge ffMode) {
			switch(ffMode) {
				case FALSE:
					return NO_FF;
				case ONLY:
					return FF_ONLY;
				default:
					return FF;
			}
		}
	}

	private Boolean commit;

	protected MergeCommand(Repository repo) {
		super(repo);
	}

	@Override
	public MergeResult call() throws GitAPIException {
		checkCallable();
		fallBackToConfiguration();
		checkParameters();

		DirCacheCheckout dco = null;
		try(RevWalk revWalk = new RevWalk(repo)) {
			Ref head = repo.exactRef(Constants.HEAD);
			if(head == null)
				throw new NoHeadException(JGitText.get().commitOnRepoWithoutHEADCurrentlyNotSupported);
			StringBuilder refLogMessage = new StringBuilder("merge ");

			Ref ref = commits.get(0);
			refLogMessage.append(ref.getName());
			ref = repo.getRefDatabase().peel(ref);
			ObjectId objectId = ref.getPeeledObjectId();
			if(objectId == null)
				objectId = ref.getObjectId();

			RevCommit srcCommit = revWalk.lookupCommit(objectId);

			ObjectId headId = head.getObjectId();
			if(headId == null) {
				revWalk.parseHeaders(srcCommit);
				dco = new DirCacheCheckout(repo,
						repo.lockDirCache(), srcCommit.getTree());
				dco.setFailOnConflict(true);
				dco.setProgressMonitor(monitor);
				dco.checkout();
				RefUpdate refUpdate = repo
						.updateRef(head.getTarget().getName());
				refUpdate.setNewObjectId(objectId);
				refUpdate.setExpectedOldObjectId(null);
				refUpdate.setRefLogMessage("initial pull", false);
				if(refUpdate.update() != Result.NEW)
					throw new NoHeadException(
							JGitText.get().commitOnRepoWithoutHEADCurrentlyNotSupported);
				setCallable(false);
				return new MergeResult(srcCommit, new ObjectId[] {
						null, srcCommit}, MergeStatus.FAST_FORWARD,
						mergeStrategy, null, null);
			}

			RevCommit headCommit = revWalk.lookupCommit(headId);

			if(revWalk.isMergedInto(srcCommit, headCommit)) {
				setCallable(false);
				return new MergeResult(srcCommit, new ObjectId[] {
						headCommit, srcCommit},
						MergeStatus.ALREADY_UP_TO_DATE, mergeStrategy, null, null);
			} else if(revWalk.isMergedInto(headCommit, srcCommit)
					&& fastForwardMode != FastForwardMode.NO_FF) {
				refLogMessage.append(": ").append(MergeStatus.FAST_FORWARD);
				dco = new DirCacheCheckout(repo,
						headCommit.getTree(), repo.lockDirCache(),
						srcCommit.getTree());
				dco.setProgressMonitor(monitor);
				dco.setFailOnConflict(true);
				dco.checkout();
				String msg = null;
				ObjectId base;
				MergeStatus mergeStatus;
				if(!squash) {
					updateHead(refLogMessage, srcCommit, headId);
					base = srcCommit;
					mergeStatus = MergeStatus.FAST_FORWARD;
				} else {
					msg = JGitText.get().squashCommitNotUpdatingHEAD;
					base = headId;
					mergeStatus = MergeStatus.FAST_FORWARD_SQUASHED;
					List<RevCommit> squashedCommits = RevWalkUtils.find(
							revWalk, srcCommit, headCommit);
					String squashMessage = new SquashMessageFormatter().format(
							squashedCommits);
					repo.writeSquashCommitMsg(squashMessage);
				}
				setCallable(false);
				return new MergeResult(base, new ObjectId[] {
						headCommit, srcCommit}, mergeStatus, mergeStrategy,
						null, msg);
			} else {
				if(fastForwardMode == FastForwardMode.FF_ONLY) {
					return new MergeResult(srcCommit,
							new ObjectId[] {headCommit, srcCommit},
							MergeStatus.ABORTED, mergeStrategy, null, null);
				}
				String mergeMessage = "";
				if(!squash) {
					if(message != null)
						mergeMessage = message;
					else
						mergeMessage = new MergeMessageFormatter().format(
								commits, head);
					repo.writeMergeCommitMsg(mergeMessage);
					repo.writeMergeHeads(Collections.singletonList(ref.getObjectId()));
				} else {
					List<RevCommit> squashedCommits = RevWalkUtils.find(
							revWalk, srcCommit, headCommit);
					String squashMessage = new SquashMessageFormatter().format(
							squashedCommits);
					repo.writeSquashCommitMsg(squashMessage);
				}
				Merger merger = mergeStrategy.newMerger(repo);
				merger.setProgressMonitor(monitor);
				boolean noProblems;
				Map<String, org.eclipse.jgit.merge.MergeResult<?>> lowLevelResults = null;
				Map<String, MergeFailureReason> failingPaths = null;
				List<String> unmergedPaths = null;
				if(merger instanceof ResolveMerger) {
					ResolveMerger resolveMerger = (ResolveMerger) merger;
					resolveMerger.setContentMergeStrategy(contentStrategy);
					resolveMerger.setCommitNames(new String[] {"BASE", "HEAD", ref.getName()});
					resolveMerger.setWorkingTreeIterator(new FileTreeIterator(repo));
					noProblems = merger.merge(headCommit, srcCommit);
					lowLevelResults = resolveMerger
							.getMergeResults();
					failingPaths = resolveMerger.getFailingPaths();
					unmergedPaths = resolveMerger.getUnmergedPaths();
					if(!resolveMerger.getModifiedFiles().isEmpty()) {
						repo.fireEvent(new WorkingTreeModifiedEvent(
								resolveMerger.getModifiedFiles(), null));
					}
				} else
					noProblems = merger.merge(headCommit, srcCommit);
				refLogMessage.append(": Merge made by ");
				if(!revWalk.isMergedInto(headCommit, srcCommit))
					refLogMessage.append(mergeStrategy.getName());
				else
					refLogMessage.append("recursive");
				refLogMessage.append('.');
				if(noProblems) {
					dco = new DirCacheCheckout(repo,
							headCommit.getTree(), repo.lockDirCache(),
							merger.getResultTreeId());
					dco.setFailOnConflict(true);
					dco.setProgressMonitor(monitor);
					dco.checkout();

					String msg = null;
					MergeStatus mergeStatus = null;
					if(!commit && squash) {
						mergeStatus = MergeStatus.MERGED_SQUASHED_NOT_COMMITTED;
					}
					if(!commit && !squash) {
						mergeStatus = MergeStatus.MERGED_NOT_COMMITTED;
					}
					if(commit && !squash) {
						try(Git git = new Git(getRepository())) {
							git.commit()
									.setReflogComment(refLogMessage.toString())
									.setInsertChangeId(insertChangeId)
									.call().getId();
						}
						mergeStatus = MergeStatus.MERGED;
						getRepository().autoGC(monitor);
					}
					if(commit && squash) {
						msg = JGitText.get().squashCommitNotUpdatingHEAD;
						headCommit.getId();
						mergeStatus = MergeStatus.MERGED_SQUASHED;
					}
					return new MergeResult(null,
							new ObjectId[] {headCommit.getId(),
									srcCommit.getId()}, mergeStatus,
							mergeStrategy, null, msg);
				}
				if(failingPaths != null) {
					repo.writeMergeCommitMsg(null);
					repo.writeMergeHeads(null);
					return new MergeResult(merger.getBaseCommitId(),
							new ObjectId[] {headCommit.getId(),
									srcCommit.getId()},
							MergeStatus.FAILED, mergeStrategy, lowLevelResults,
							failingPaths, null);
				}
				CommitConfig cfg = repo.getConfig().get(CommitConfig.KEY);
				char commentChar = cfg.getCommentChar(message);
				String mergeMessageWithConflicts = new MergeMessageFormatter()
						.formatWithConflicts(mergeMessage, unmergedPaths,
								commentChar);
				repo.writeMergeCommitMsg(mergeMessageWithConflicts);
				return new MergeResult(merger.getBaseCommitId(),
						new ObjectId[] {headCommit.getId(),
								srcCommit.getId()},
						MergeStatus.CONFLICTING, mergeStrategy, lowLevelResults,
						null);
			}
		} catch(org.eclipse.jgit.errors.CheckoutConflictException e) {
			List<String> conflicts = (dco == null) ? Collections
					.emptyList() : dco.getConflicts();
			throw new CheckoutConflictException(conflicts, e);
		} catch(IOException e) {
			throw new JGitInternalException(
					MessageFormat.format(
							JGitText.get().exceptionCaughtDuringExecutionOfMergeCommand, e), e);
		}
	}

	private void checkParameters() throws InvalidMergeHeadsException {
		if(squash && fastForwardMode == FastForwardMode.NO_FF) {
			throw new JGitInternalException(
					JGitText.get().cannotCombineSquashWithNoff);
		}

		if(commits.size() != 1)
			throw new InvalidMergeHeadsException(commits.isEmpty()
					? JGitText.get().noMergeHeadSpecified : MessageFormat.format(
					JGitText.get().mergeStrategyDoesNotSupportHeads, mergeStrategy.getName(), commits.size()));
	}

	private void fallBackToConfiguration() {
		MergeConfig config = MergeConfig.getConfigForCurrentBranch(repo);
		if(squash == null) squash = config.isSquash();
		if(commit == null) commit = config.isCommit();
		if(fastForwardMode == null) fastForwardMode = config.getFastForwardMode();
	}

	private void updateHead(StringBuilder refLogMessage, ObjectId newHeadId,
							ObjectId oldHeadID) throws IOException,
			ConcurrentRefUpdateException {
		RefUpdate refUpdate = repo.updateRef(Constants.HEAD);
		refUpdate.setNewObjectId(newHeadId);
		refUpdate.setRefLogMessage(refLogMessage.toString(), false);
		refUpdate.setExpectedOldObjectId(oldHeadID);
		Result rc = refUpdate.update();
		switch(rc) {
			case NEW:
			case FAST_FORWARD:
				return;
			case REJECTED:
			case LOCK_FAILURE:
				throw new ConcurrentRefUpdateException(
						JGitText.get().couldNotLockHEAD, rc);
			default:
				throw new JGitInternalException(MessageFormat.format(
						JGitText.get().updatingRefFailed, Constants.HEAD,
						newHeadId.toString(), rc));
		}
	}

	public MergeCommand setStrategy(MergeStrategy mergeStrategy) {
		checkCallable();
		this.mergeStrategy = mergeStrategy;
		return this;
	}

	public MergeCommand setContentMergeStrategy(ContentMergeStrategy strategy) {
		checkCallable();
		this.contentStrategy = strategy;
		return this;
	}

	public MergeCommand include(Ref aCommit) {
		checkCallable();
		commits.add(aCommit);
		return this;
	}

	public MergeCommand include(AnyObjectId aCommit) {
		return include(aCommit.getName(), aCommit);
	}

	public MergeCommand include(String name, AnyObjectId aCommit) {
		return include(new ObjectIdRef.Unpeeled(Storage.LOOSE, name,
				aCommit.copy()));
	}

	public MergeCommand setFastForward(
			@Nullable FastForwardMode fastForwardMode) {
		checkCallable();
		this.fastForwardMode = fastForwardMode;
		return this;
	}

	public MergeCommand setCommit(boolean commit) {
		this.commit = commit;
		return this;
	}

	public MergeCommand setProgressMonitor(ProgressMonitor monitor) {
		if(monitor == null) {
			monitor = NullProgressMonitor.INSTANCE;
		}
		this.monitor = monitor;
		return this;
	}
}
