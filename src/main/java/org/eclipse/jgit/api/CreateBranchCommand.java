/*
 * Copyright (C) 2010, Mathias Kinzler <mathias.kinzler@sap.com>
 * Copyright (C) 2010, Chris Aniszczyk <caniszczyk@gmail.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.api;

import org.eclipse.jgit.api.errors.*;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

import java.io.IOException;
import java.text.MessageFormat;

import static org.eclipse.jgit.lib.Constants.HEAD;
import static org.eclipse.jgit.lib.Constants.R_HEADS;

public class CreateBranchCommand extends GitCommand<Ref> {

	private String name;
	private SetupUpstreamMode upstreamMode;
	private final String startPoint = HEAD;
	private RevCommit startCommit;

	public enum SetupUpstreamMode {
		TRACK,
		NOTRACK,
		SET_UPSTREAM
	}

	protected CreateBranchCommand(Repository repo) {
		super(repo);
	}

	@Override
	public Ref call() throws GitAPIException {
		checkCallable();
		processOptions();
		try(RevWalk revWalk = new RevWalk(repo)) {
			Ref refToCheck = repo.findRef(name);
			boolean exists = refToCheck != null
					&& refToCheck.getName().startsWith(R_HEADS);
			boolean force = false;
			if(!force && exists)
				throw new RefAlreadyExistsException(MessageFormat.format(
						JGitText.get().refAlreadyExists1, name));

			ObjectId startAt = getStartPointObjectId();
			String startPointFullName = null;
			Ref baseRef = repo.findRef(startPoint);
			if(baseRef != null)
				startPointFullName = baseRef.getName();

			String refLogMessage;
			String baseBranch = "";
			if(startPointFullName == null) {
				String baseCommit;
				if(startCommit != null)
					baseCommit = startCommit.getShortMessage();
				else {
					RevCommit commit = revWalk.parseCommit(repo.resolve(getStartPointOrHead()));
					baseCommit = commit.getShortMessage();
				}
				refLogMessage = "branch: Created from commit " + baseCommit;

			} else if(startPointFullName.startsWith(R_HEADS) || startPointFullName.startsWith(Constants.R_REMOTES)) {
				baseBranch = startPointFullName;
				refLogMessage = "branch: Created from branch " + baseBranch;
			} else {
				startAt = revWalk.peel(revWalk.parseAny(startAt));
				refLogMessage = "branch: Created from tag " + startPointFullName;
			}

			RefUpdate updateRef = repo.updateRef(R_HEADS + name);
			updateRef.setNewObjectId(startAt);
			updateRef.setRefLogMessage(refLogMessage, false);
			Result updateResult;
			updateResult = updateRef.update();

			setCallable(false);

			boolean ok = updateResult == Result.NEW;

			if(!ok) throw new JGitInternalException(MessageFormat.format(JGitText
					.get().createBranchUnexpectedResult, updateResult.name()));

			Ref result = repo.findRef(name);
			if(result == null) throw new JGitInternalException(JGitText.get().createBranchFailedUnknownReason);

			if(baseBranch.length() == 0) return result;

			boolean doConfigure;
			if(upstreamMode == SetupUpstreamMode.SET_UPSTREAM || upstreamMode == SetupUpstreamMode.TRACK)
				doConfigure = true;
			else if(upstreamMode == SetupUpstreamMode.NOTRACK)
				doConfigure = false;
			else {
				String autosetupflag = repo.getConfig().getString(
						ConfigConstants.CONFIG_BRANCH_SECTION, null, ConfigConstants.CONFIG_KEY_AUTOSETUPMERGE);
				if("false".equals(autosetupflag)) {
					doConfigure = false;
				} else if("always".equals(autosetupflag)) {
					doConfigure = true;
				} else {
					doConfigure = baseBranch.startsWith(Constants.R_REMOTES);
				}
			}

			if(doConfigure) {
				StoredConfig config = repo.getConfig();

				String remoteName = repo.getRemoteName(baseBranch);
				if(remoteName != null) {
					String branchName = repo.shortenRemoteBranchName(baseBranch);
					config.setString(ConfigConstants.CONFIG_BRANCH_SECTION, name, ConfigConstants.CONFIG_KEY_REMOTE, remoteName);
					config.setString(ConfigConstants.CONFIG_BRANCH_SECTION, name, ConfigConstants.CONFIG_KEY_MERGE,
							Constants.R_HEADS + branchName);
				} else {
					config.setString(ConfigConstants.CONFIG_BRANCH_SECTION, name, ConfigConstants.CONFIG_KEY_REMOTE, ".");
					config.setString(ConfigConstants.CONFIG_BRANCH_SECTION, name, ConfigConstants.CONFIG_KEY_MERGE, baseBranch);
				}
				config.save();
			}
			return result;
		} catch(IOException ioe) {
			throw new JGitInternalException(ioe.getMessage(), ioe);
		}
	}

	private ObjectId getStartPointObjectId() throws
			RefNotFoundException, IOException {
		if(startCommit != null)
			return startCommit.getId();
		String startPointOrHead = getStartPointOrHead();
		ObjectId result = repo.resolve(startPointOrHead);
		if(result == null)
			throw new RefNotFoundException(MessageFormat.format(
					JGitText.get().refNotResolved, startPointOrHead));
		return result;
	}

	private String getStartPointOrHead() {
		return startPoint;
	}

	private void processOptions() throws InvalidRefNameException {
		if(name == null
				|| !Repository.isValidRefName(R_HEADS + name)
				|| !isValidBranchName(name))
			throw new InvalidRefNameException(MessageFormat.format(JGitText
					.get().branchNameInvalid, name == null ? "<null>" : name));
	}

	public static boolean isValidBranchName(String branchName) {
		if(HEAD.equals(branchName)) {
			return false;
		}
		return !branchName.startsWith("-");
	}

	public CreateBranchCommand setName(String name) {
		checkCallable();
		this.name = name;
		return this;
	}

}
