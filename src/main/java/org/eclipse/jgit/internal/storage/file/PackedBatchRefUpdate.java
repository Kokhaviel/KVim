/*
 * Copyright (C) 2017, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.file;

import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.errors.LockFailedException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.internal.storage.file.RefDirectory.PackedRefList;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.util.RefList;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.*;

import static java.util.stream.Collectors.toList;
import static org.eclipse.jgit.transport.ReceiveCommand.Result.*;

class PackedBatchRefUpdate extends BatchRefUpdate {
	private final RefDirectory refdb;

	PackedBatchRefUpdate(RefDirectory refdb) {
		super(refdb);
		this.refdb = refdb;
	}

	@Override
	public void execute(RevWalk walk, ProgressMonitor monitor,
						List<String> options) throws IOException {
		if(!isAtomic()) {
			super.execute(walk, monitor, options);
			return;
		}
		List<ReceiveCommand> pending =
				ReceiveCommand.filter(getCommands(), NOT_ATTEMPTED);
		if(pending.isEmpty()) return;

		if(pending.size() == 1) {
			super.execute(walk, monitor, options);
			return;
		}
		if(containsSymrefs(pending)) {
			reject(pending.get(0), REJECTED_OTHER_REASON,
					JGitText.get().atomicSymRefNotSupported, pending);
			return;
		}

		if(!blockUntilTimestamps()) return;
		if(!checkConflictingNames(pending)) return;
		if(!checkObjectExistence(walk, pending)) return;
		if(!checkNonFastForwards(walk, pending)) return;


		try {
			refdb.pack(
					pending.stream().map(ReceiveCommand::getRefName).collect(toList()));
		} catch(LockFailedException e) {
			lockFailure(pending.get(0), pending);
			return;
		}

		Map<String, LockFile> locks = null;
		refdb.inProcessPackedRefsLock.lock();
		try {
			PackedRefList oldPackedList;
			if(!refdb.isInClone()) {
				locks = lockLooseRefs(pending);
				if(locks == null) {
					return;
				}
				oldPackedList = refdb.pack(locks);
			} else {
				oldPackedList = refdb.getPackedRefs();
			}
			RefList<Ref> newRefs = applyUpdates(walk, oldPackedList, pending);
			if(newRefs == null) {
				return;
			}
			LockFile packedRefsLock = refdb.lockPackedRefs();
			if(packedRefsLock == null) {
				lockFailure(pending.get(0), pending);
				return;
			}

			refdb.commitPackedRefs(packedRefsLock, newRefs, oldPackedList, true);
		} finally {
			try {
				unlockAll(locks);
			} finally {
				refdb.inProcessPackedRefsLock.unlock();
			}
		}

		refdb.fireRefsChanged();
		pending.forEach(c -> c.setResult(ReceiveCommand.Result.OK));
		writeReflog(pending);
	}

	private static boolean containsSymrefs(List<ReceiveCommand> commands) {
		for(ReceiveCommand cmd : commands) {
			if(cmd.getOldSymref() != null || cmd.getNewSymref() != null) {
				return true;
			}
		}
		return false;
	}

	private boolean checkConflictingNames(List<ReceiveCommand> commands)
			throws IOException {
		Set<String> takenNames = new HashSet<>();
		Set<String> takenPrefixes = new HashSet<>();
		Set<String> deletes = new HashSet<>();
		for(ReceiveCommand cmd : commands) {
			if(cmd.getType() != ReceiveCommand.Type.DELETE) {
				takenNames.add(cmd.getRefName());
				addPrefixesTo(cmd.getRefName(), takenPrefixes);
			} else {
				deletes.add(cmd.getRefName());
			}
		}
		Set<String> initialRefs = refdb.getRefs(RefDatabase.ALL).keySet();
		for(String name : initialRefs) {
			if(!deletes.contains(name)) {
				takenNames.add(name);
				addPrefixesTo(name, takenPrefixes);
			}
		}

		for(ReceiveCommand cmd : commands) {
			if(cmd.getType() != ReceiveCommand.Type.DELETE &&
					takenPrefixes.contains(cmd.getRefName())) {
				lockFailure(cmd, commands);
				return false;
			}
			for(String prefix : getPrefixes(cmd.getRefName())) {
				if(takenNames.contains(prefix)) {
					lockFailure(cmd, commands);
					return false;
				}
			}
		}
		return true;
	}

	private boolean checkObjectExistence(RevWalk walk,
										 List<ReceiveCommand> commands) throws IOException {
		for(ReceiveCommand cmd : commands) {
			try {
				if(!cmd.getNewId().equals(ObjectId.zeroId())) {
					walk.parseAny(cmd.getNewId());
				}
			} catch(MissingObjectException e) {
				reject(cmd, ReceiveCommand.Result.REJECTED_MISSING_OBJECT, commands);
				return false;
			}
		}
		return true;
	}

	private boolean checkNonFastForwards(RevWalk walk,
										 List<ReceiveCommand> commands) throws IOException {
		if(isAllowNonFastForwards()) {
			return true;
		}
		for(ReceiveCommand cmd : commands) {
			cmd.updateType(walk);
			if(cmd.getType() == ReceiveCommand.Type.UPDATE_NONFASTFORWARD) {
				reject(cmd, REJECTED_NONFASTFORWARD, commands);
				return false;
			}
		}
		return true;
	}

	@Nullable
	private Map<String, LockFile> lockLooseRefs(List<ReceiveCommand> commands)
			throws IOException {
		ReceiveCommand failed = null;
		Map<String, LockFile> locks = new HashMap<>();
		try {
			RETRY:
			for(int ms : refdb.getRetrySleepMs()) {
				unlockAll(locks);
				locks.clear();
				RefDirectory.sleep(ms);

				for(ReceiveCommand c : commands) {
					String name = c.getRefName();
					LockFile lock = new LockFile(refdb.fileFor(name));
					if(locks.put(name, lock) != null) {
						throw new IOException(
								MessageFormat.format(JGitText.get().duplicateRef, name));
					}
					if(!lock.lock()) {
						failed = c;
						continue RETRY;
					}
				}
				Map<String, LockFile> result = locks;
				locks = null;
				return result;
			}
		} finally {
			unlockAll(locks);
		}
		lockFailure(failed != null ? failed : commands.get(0), commands);
		return null;
	}

	private static RefList<Ref> applyUpdates(RevWalk walk, RefList<Ref> refs,
											 List<ReceiveCommand> commands) throws IOException {
		commands.sort(Comparator.comparing(ReceiveCommand::getRefName));

		int delta = 0;
		for(ReceiveCommand c : commands) {
			switch(c.getType()) {
				case DELETE:
					delta--;
					break;
				case CREATE:
					delta++;
					break;
				default:
			}
		}

		RefList.Builder<Ref> b = new RefList.Builder<>(refs.size() + delta);
		int refIdx = 0;
		int cmdIdx = 0;
		while(refIdx < refs.size() || cmdIdx < commands.size()) {
			Ref ref = (refIdx < refs.size()) ? refs.get(refIdx) : null;
			ReceiveCommand cmd = (cmdIdx < commands.size())
					? commands.get(cmdIdx)
					: null;
			int cmp;
			if(ref != null && cmd != null) {
				cmp = ref.getName().compareTo(cmd.getRefName());
			} else if(ref == null) {
				cmp = 1;
			} else {
				cmp = -1;
			}

			if(cmp < 0) {
				b.add(ref);
				refIdx++;
			} else if(cmp > 0) {
				assert cmd != null;
				if(cmd.getType() != ReceiveCommand.Type.CREATE) {
					lockFailure(cmd, commands);
					return null;
				}

				b.add(peeledRef(walk, cmd));
				cmdIdx++;
			} else {
				if(!cmd.getOldId().equals(ref.getObjectId())) {
					lockFailure(cmd, commands);
					return null;
				}

				if(cmd.getType() != ReceiveCommand.Type.DELETE) {
					b.add(peeledRef(walk, cmd));
				}
				cmdIdx++;
				refIdx++;
			}
		}
		return b.toRefList();
	}

	private void writeReflog(List<ReceiveCommand> commands) {
		PersonIdent ident = getRefLogIdent();
		if(ident == null) {
			ident = new PersonIdent(refdb.getRepository());
		}
		for(ReceiveCommand cmd : commands) {
			if(cmd.getResult() != ReceiveCommand.Result.OK) {
				continue;
			}
			String name = cmd.getRefName();

			if(cmd.getType() == ReceiveCommand.Type.DELETE) {
				try {
					RefDirectory.delete(refdb.logFor(name), RefDirectory.levelsIn(name));
				} catch(IOException ignored) {
				}
				continue;
			}

			if(isRefLogDisabled(cmd)) {
				continue;
			}

			String msg = getRefLogMessage(cmd);
			if(isRefLogIncludingResult(cmd)) {
				String strResult = toResultString(cmd);
				if(strResult != null) {
					msg = msg.isEmpty()
							? strResult : msg + ": " + strResult;
				}
			}
			try {
				new ReflogWriter(refdb, isForceRefLog(cmd)).log(name, cmd.getOldId(), cmd.getNewId(), ident, msg);
			} catch(IOException ignored) {
			}
		}
	}

	private String toResultString(ReceiveCommand cmd) {
		switch(cmd.getType()) {
			case CREATE:
				return ReflogEntry.PREFIX_CREATED;
			case UPDATE:
				return isAllowNonFastForwards()
						? ReflogEntry.PREFIX_FORCED_UPDATE : ReflogEntry.PREFIX_FAST_FORWARD;
			case UPDATE_NONFASTFORWARD:
				return ReflogEntry.PREFIX_FORCED_UPDATE;
			default:
				return null;
		}
	}

	private static Ref peeledRef(RevWalk walk, ReceiveCommand cmd)
			throws IOException {
		ObjectId newId = cmd.getNewId().copy();
		RevObject obj = walk.parseAny(newId);
		if(obj instanceof RevTag) {
			return new ObjectIdRef.PeeledTag(
					Ref.Storage.PACKED, cmd.getRefName(), newId, walk.peel(obj).copy());
		}
		return new ObjectIdRef.PeeledNonTag(
				Ref.Storage.PACKED, cmd.getRefName(), newId);
	}

	private static void unlockAll(@Nullable Map<?, LockFile> locks) {
		if(locks != null) {
			locks.values().forEach(LockFile::unlock);
		}
	}

	private static void lockFailure(ReceiveCommand cmd,
									List<ReceiveCommand> commands) {
		reject(cmd, LOCK_FAILURE, commands);
	}

	private static void reject(ReceiveCommand cmd, ReceiveCommand.Result result,
							   List<ReceiveCommand> commands) {
		reject(cmd, result, null, commands);
	}

	private static void reject(ReceiveCommand cmd, ReceiveCommand.Result result,
							   String why, List<ReceiveCommand> commands) {
		cmd.setResult(result, why);
		for(ReceiveCommand c2 : commands) {
			if(c2.getResult() == ReceiveCommand.Result.OK) {
				c2.setResult(ReceiveCommand.Result.NOT_ATTEMPTED);
			}
		}
		ReceiveCommand.abort(commands);
	}
}
