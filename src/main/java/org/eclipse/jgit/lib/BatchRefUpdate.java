/*
 * Copyright (C) 2008-2012, Google Inc.
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.lib;

import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.util.time.ProposedTimestamp;

import java.io.IOException;
import java.text.MessageFormat;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeoutException;

import static org.eclipse.jgit.transport.ReceiveCommand.Result.NOT_ATTEMPTED;
import static org.eclipse.jgit.transport.ReceiveCommand.Result.REJECTED_OTHER_REASON;

public class BatchRefUpdate {

	protected static final Duration MAX_WAIT = Duration.ofSeconds(5);

	private final RefDatabase refdb;
	private final List<ReceiveCommand> commands;
	private boolean allowNonFastForwards;
	private PersonIdent refLogIdent;
	private String refLogMessage;
	private boolean refLogIncludeResult;
	private boolean forceRefLog;
	private boolean atomic;
	private List<ProposedTimestamp> timestamps;

	protected BatchRefUpdate(RefDatabase refdb) {
		this.refdb = refdb;
		this.commands = new ArrayList<>();
		this.atomic = refdb.performsAtomicTransactions();
	}

	public boolean isAllowNonFastForwards() {
		return allowNonFastForwards;
	}

	public BatchRefUpdate setAllowNonFastForwards(boolean allow) {
		allowNonFastForwards = allow;
		return this;
	}

	public PersonIdent getRefLogIdent() {
		return refLogIdent;
	}

	public BatchRefUpdate setRefLogIdent(PersonIdent pi) {
		refLogIdent = pi;
		return this;
	}

	@Nullable
	public String getRefLogMessage() {
		return refLogMessage;
	}

	public boolean isRefLogIncludingResult() {
		return refLogIncludeResult;
	}

	public BatchRefUpdate setRefLogMessage(String msg, boolean appendStatus) {
		if(msg == null && !appendStatus)
			disableRefLog();
		else if(msg == null) {
			refLogMessage = "";
			refLogIncludeResult = true;
		} else {
			refLogMessage = msg;
			refLogIncludeResult = appendStatus;
		}
		return this;
	}

	public BatchRefUpdate disableRefLog() {
		refLogMessage = null;
		refLogIncludeResult = false;
		return this;
	}

	public boolean isRefLogDisabled() {
		return refLogMessage == null;
	}

	protected boolean isForceRefLog() {
		return forceRefLog;
	}

	public BatchRefUpdate setAtomic(boolean atomic) {
		this.atomic = atomic;
		return this;
	}

	public boolean isAtomic() {
		return atomic;
	}

	public List<ReceiveCommand> getCommands() {
		return Collections.unmodifiableList(commands);
	}

	public BatchRefUpdate addCommand(ReceiveCommand cmd) {
		commands.add(cmd);
		return this;
	}

	public BatchRefUpdate addCommand(Collection<ReceiveCommand> cmd) {
		commands.addAll(cmd);
		return this;
	}

	public void execute(RevWalk walk, ProgressMonitor monitor,
						List<String> options) throws IOException {

		if(atomic && !refdb.performsAtomicTransactions()) {
			for(ReceiveCommand c : commands) {
				if(c.getResult() == NOT_ATTEMPTED) {
					c.setResult(REJECTED_OTHER_REASON,
							JGitText.get().atomicRefUpdatesNotSupported);
				}
			}
			return;
		}
		if(!blockUntilTimestamps()) {
			return;
		}

		monitor.beginTask(JGitText.get().updatingReferences, commands.size());
		List<ReceiveCommand> commands2 = new ArrayList<>(
				commands.size());
		for(ReceiveCommand cmd : commands) {
			try {
				if(cmd.getResult() == NOT_ATTEMPTED) {
					if(isMissing(walk, cmd.getOldId())
							|| isMissing(walk, cmd.getNewId())) {
						cmd.setResult(ReceiveCommand.Result.REJECTED_MISSING_OBJECT);
						continue;
					}
					cmd.updateType(walk);
					switch(cmd.getType()) {
						case CREATE:
						case UPDATE:
						case UPDATE_NONFASTFORWARD:
							commands2.add(cmd);
							break;
						case DELETE:
							RefUpdate rud = newUpdate(cmd);
							monitor.update(1);
							cmd.setResult(rud.delete(walk));
					}
				}
			} catch(IOException err) {
				cmd.setResult(
						REJECTED_OTHER_REASON,
						MessageFormat.format(JGitText.get().lockError,
								err.getMessage()));
			}
		}
		if(!commands2.isEmpty()) {
			for(ReceiveCommand cmd : commands2) {
				try {
					if(cmd.getResult() == NOT_ATTEMPTED) {
						cmd.updateType(walk);
						RefUpdate ru = newUpdate(cmd);
						switch(cmd.getType()) {
							case DELETE:
								break;
							case UPDATE:
							case UPDATE_NONFASTFORWARD:
								RefUpdate ruu = newUpdate(cmd);
								cmd.setResult(ruu.update(walk));
								break;
							case CREATE:
								cmd.setResult(ru.update(walk));
								break;
						}
					}
				} catch(IOException err) {
					cmd.setResult(REJECTED_OTHER_REASON, MessageFormat.format(
							JGitText.get().lockError, err.getMessage()));
				} finally {
					monitor.update(1);
				}
			}
		}
		monitor.endTask();
	}

	private static boolean isMissing(RevWalk walk, ObjectId id)
			throws IOException {
		if(id.equals(ObjectId.zeroId())) {
			return false;
		}
		try {
			walk.parseAny(id);
			return false;
		} catch(MissingObjectException e) {
			return true;
		}
	}

	protected boolean blockUntilTimestamps() {
		if(timestamps == null) {
			return true;
		}
		try {
			ProposedTimestamp.blockUntil(timestamps, BatchRefUpdate.MAX_WAIT);
			return true;
		} catch(TimeoutException | InterruptedException e) {
			String msg = JGitText.get().timeIsUncertain;
			for(ReceiveCommand c : commands) {
				if(c.getResult() == NOT_ATTEMPTED) {
					c.setResult(REJECTED_OTHER_REASON, msg);
				}
			}
			return false;
		}
	}

	public void execute(RevWalk walk, ProgressMonitor monitor)
			throws IOException {
		execute(walk, monitor, null);
	}

	protected static Collection<String> getPrefixes(String name) {
		Collection<String> ret = new HashSet<>();
		addPrefixesTo(name, ret);
		return ret;
	}

	protected static void addPrefixesTo(String name, Collection<String> out) {
		int p1 = name.indexOf('/');
		while(p1 > 0) {
			out.add(name.substring(0, p1));
			p1 = name.indexOf('/', p1 + 1);
		}
	}

	protected RefUpdate newUpdate(ReceiveCommand cmd) throws IOException {
		RefUpdate ru = refdb.newUpdate(cmd.getRefName(), false);
		if(isRefLogDisabled(cmd)) {
			ru.disableRefLog();
		} else {
			ru.setRefLogIdent(refLogIdent);
			ru.setRefLogMessage(getRefLogMessage(cmd), isRefLogIncludingResult(cmd));
			ru.setForceRefLog(isForceRefLog(cmd));
		}
		ru.setPushCertificate();
		switch(cmd.getType()) {
			case DELETE:
				if(!ObjectId.zeroId().equals(cmd.getOldId()))
					ru.setExpectedOldObjectId(cmd.getOldId());
				ru.setForceUpdate(true);
				return ru;

			case CREATE:
			case UPDATE:
			case UPDATE_NONFASTFORWARD:
			default:
				ru.setForceUpdate(isAllowNonFastForwards());
				ru.setExpectedOldObjectId(cmd.getOldId());
				ru.setNewObjectId(cmd.getNewId());
				return ru;
		}
	}

	protected boolean isRefLogDisabled(ReceiveCommand cmd) {
		return cmd.hasCustomRefLog() ? cmd.isRefLogDisabled() : isRefLogDisabled();
	}

	protected String getRefLogMessage(ReceiveCommand cmd) {
		return cmd.hasCustomRefLog() ? cmd.getRefLogMessage() : getRefLogMessage();
	}

	protected boolean isRefLogIncludingResult(ReceiveCommand cmd) {
		return cmd.hasCustomRefLog()
				? cmd.isRefLogIncludingResult() : isRefLogIncludingResult();
	}

	protected boolean isForceRefLog(ReceiveCommand cmd) {
		Boolean isForceRefLog = cmd.isForceRefLog();
		return isForceRefLog != null ? isForceRefLog
				: isForceRefLog();
	}

	@Override
	public String toString() {
		StringBuilder r = new StringBuilder();
		r.append(getClass().getSimpleName()).append('[');
		if(commands.isEmpty())
			return r.append(']').toString();

		r.append('\n');
		for(ReceiveCommand cmd : commands) {
			r.append("  ");
			r.append(cmd);
			r.append("  (").append(cmd.getResult());
			if(cmd.getMessage() != null) {
				r.append(": ").append(cmd.getMessage());
			}
			r.append(")\n");
		}
		return r.append(']').toString();
	}
}
