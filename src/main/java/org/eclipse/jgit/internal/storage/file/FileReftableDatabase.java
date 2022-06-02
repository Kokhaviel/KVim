/*
 * Copyright (C) 2019 Google LLC and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.file;

import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.events.RefsChangedEvent;
import org.eclipse.jgit.internal.storage.reftable.MergedReftable;
import org.eclipse.jgit.internal.storage.reftable.ReftableBatchRefUpdate;
import org.eclipse.jgit.internal.storage.reftable.ReftableDatabase;
import org.eclipse.jgit.internal.storage.reftable.ReftableWriter;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.util.FileUtils;
import org.eclipse.jgit.util.RefList;
import org.eclipse.jgit.util.RefMap;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static org.eclipse.jgit.lib.Ref.Storage.NEW;
import static org.eclipse.jgit.lib.Ref.UNDEFINED_UPDATE_INDEX;

public class FileReftableDatabase extends RefDatabase {
	private final ReftableDatabase reftableDatabase;
	private final FileRepository fileRepository;
	private final FileReftableStack reftableStack;

	FileReftableDatabase(FileRepository repo) throws IOException {
		this(repo, new File(new File(repo.getDirectory(), Constants.REFTABLE),
				Constants.TABLES_LIST));
	}

	FileReftableDatabase(FileRepository repo, File refstackName) throws IOException {
		this.fileRepository = repo;
		this.reftableStack = new FileReftableStack(refstackName,
				new File(fileRepository.getDirectory(), Constants.REFTABLE),
				() -> fileRepository.fireEvent(new RefsChangedEvent()), fileRepository::getConfig);
		this.reftableDatabase = new ReftableDatabase() {

			@Override
			public MergedReftable openMergedReftable() {
				return reftableStack.getMergedReftable();
			}
		};
	}

	ReflogReader getReflogReader(String refname) throws IOException {
		return reftableDatabase.getReflogReader(refname);
	}

	public void compactFully() throws IOException {
		Lock l = reftableDatabase.getLock();
		l.lock();
		try {
			reftableStack.compactFully();
			reftableDatabase.clearCache();
		} finally {
			l.unlock();
		}
	}

	private ReentrantLock getLock() {
		return reftableDatabase.getLock();
	}

	@Override
	public boolean performsAtomicTransactions() {
		return true;
	}

	@NonNull
	@Override
	public BatchRefUpdate newBatchUpdate() {
		return new FileReftableBatchRefUpdate(this, fileRepository);
	}

	@Override
	public RefUpdate newUpdate(String refName, boolean detach)
			throws IOException {
		boolean detachingSymbolicRef = false;
		Ref ref = exactRef(refName);

		if(ref == null) {
			ref = new ObjectIdRef.Unpeeled(NEW, refName, null);
		} else {
			detachingSymbolicRef = detach && ref.isSymbolic();
		}

		RefUpdate update = new FileReftableRefUpdate(ref);
		if(detachingSymbolicRef) {
			update.setDetachingSymbolicRef();
		}
		return update;
	}

	@Override
	public Ref exactRef(String name) throws IOException {
		return reftableDatabase.exactRef(name);
	}

	@Override
	public List<Ref> getRefs() throws IOException {
		return super.getRefs();
	}

	@Override
	public Map<String, Ref> getRefs(String prefix) throws IOException {
		List<Ref> refs = reftableDatabase.getRefsByPrefix(prefix);
		RefList.Builder<Ref> builder = new RefList.Builder<>(refs.size());
		for(Ref r : refs) {
			builder.add(r);
		}
		return new RefMap(prefix, builder.toRefList(), RefList.emptyList(),
				RefList.emptyList());
	}

	@Override
	public List<Ref> getAdditionalRefs() {
		return Collections.emptyList();
	}

	@Override
	public Ref peel(Ref ref) throws IOException {
		Ref oldLeaf = ref.getLeaf();
		if(oldLeaf.isPeeled() || oldLeaf.getObjectId() == null) {
			return ref;
		}
		return recreate(ref, doPeel(oldLeaf), hasVersioning());

	}

	private Ref doPeel(Ref leaf) throws IOException {
		try(RevWalk rw = new RevWalk(fileRepository)) {
			RevObject obj = rw.parseAny(leaf.getObjectId());
			if(obj instanceof RevTag) {
				return new ObjectIdRef.PeeledTag(leaf.getStorage(),
						leaf.getName(), leaf.getObjectId(), rw.peel(obj).copy(),
						hasVersioning() ? leaf.getUpdateIndex()
								: UNDEFINED_UPDATE_INDEX);
			}
			return new ObjectIdRef.PeeledNonTag(leaf.getStorage(),
					leaf.getName(), leaf.getObjectId(),
					hasVersioning() ? leaf.getUpdateIndex()
							: UNDEFINED_UPDATE_INDEX);

		}
	}

	private static Ref recreate(Ref old, Ref leaf, boolean hasVersioning) {
		if(old.isSymbolic()) {
			Ref dst = recreate(old.getTarget(), leaf, hasVersioning);
			return new SymbolicRef(old.getName(), dst,
					hasVersioning ? old.getUpdateIndex()
							: UNDEFINED_UPDATE_INDEX);
		}
		return leaf;
	}

	@Override
	public boolean isNameConflicting(String name) throws IOException {
		return reftableDatabase.isNameConflicting(name, new TreeSet<>(),
				new HashSet<>());
	}

	@Override
	public void close() {
		reftableStack.close();
	}

	@Override
	public void create() throws IOException {
		FileUtils.mkdir(
				new File(fileRepository.getDirectory(), Constants.REFTABLE),
				true);
	}

	private boolean addReftable(FileReftableStack.Writer w) throws IOException {
		if(!reftableStack.addReftable(w)) {
			reftableStack.reload();
			reftableDatabase.clearCache();
			return false;
		}
		reftableDatabase.clearCache();

		return true;
	}

	private class FileReftableBatchRefUpdate extends ReftableBatchRefUpdate {
		FileReftableBatchRefUpdate(FileReftableDatabase db,
								   Repository repository) {
			super(db, db.reftableDatabase, db.getLock(), repository);
		}

		@Override
		protected void applyUpdates(List<Ref> newRefs,
									List<ReceiveCommand> pending) throws IOException {
			if(!addReftable(rw -> write(rw, newRefs, pending))) {
				for(ReceiveCommand c : pending) {
					if(c.getResult() == ReceiveCommand.Result.NOT_ATTEMPTED) {
						c.setResult(RefUpdate.Result.LOCK_FAILURE);
					}
				}
			}
		}
	}

	private class FileReftableRefUpdate extends RefUpdate {
		FileReftableRefUpdate(Ref ref) {
			super(ref);
		}

		@Override
		protected RefDatabase getRefDatabase() {
			return FileReftableDatabase.this;
		}

		@Override
		protected Repository getRepository() {
			return FileReftableDatabase.this.fileRepository;
		}

		@Override
		protected void unlock() {
		}

		private RevWalk rw;

		private Ref dstRef;

		@Override
		public Result update(RevWalk walk) throws IOException {
			try {
				rw = walk;
				return super.update(walk);
			} finally {
				rw = null;
			}
		}

		@Override
		protected boolean tryLock(boolean deref) throws IOException {
			dstRef = getRef();
			if(deref) {
				dstRef = dstRef.getLeaf();
			}

			Ref derefed = exactRef(dstRef.getName());
			if(derefed != null) {
				setOldObjectId(derefed.getObjectId());
			}

			return true;
		}

		void writeUpdate(ReftableWriter w) throws IOException {
			Ref newRef = null;
			if(rw != null && !ObjectId.zeroId().equals(getNewObjectId())) {
				RevObject obj = rw.parseAny(getNewObjectId());
				if(obj instanceof RevTag) {
					newRef = new ObjectIdRef.PeeledTag(Ref.Storage.PACKED,
							dstRef.getName(), getNewObjectId(),
							rw.peel(obj).copy());
				}
			}
			if(newRef == null) {
				newRef = new ObjectIdRef.PeeledNonTag(Ref.Storage.PACKED,
						dstRef.getName(), getNewObjectId());
			}

			long idx = reftableDatabase.nextUpdateIndex();
			w.setMinUpdateIndex(idx).setMaxUpdateIndex(idx).begin()
					.writeRef(newRef);

			ObjectId oldId = getOldObjectId();
			if(oldId == null) {
				oldId = ObjectId.zeroId();
			}
			w.writeLog(dstRef.getName(), idx, getRefLogIdent(), oldId,
					getNewObjectId(), getRefLogMessage());
		}

		@Override
		public PersonIdent getRefLogIdent() {
			PersonIdent who = super.getRefLogIdent();
			if(who == null) {
				who = new PersonIdent(getRepository());
			}
			return who;
		}

		void writeDelete(ReftableWriter w) throws IOException {
			Ref newRef = new ObjectIdRef.Unpeeled(Ref.Storage.NEW,
					dstRef.getName(), null);
			long idx = reftableDatabase.nextUpdateIndex();
			w.setMinUpdateIndex(idx).setMaxUpdateIndex(idx).begin()
					.writeRef(newRef);

			ObjectId oldId = ObjectId.zeroId();
			Ref old = exactRef(dstRef.getName());
			if(old != null) {
				old = old.getLeaf();
				if(old.getObjectId() != null) {
					oldId = old.getObjectId();
				}
			}

			w.writeLog(dstRef.getName(), idx, getRefLogIdent(), oldId,
					ObjectId.zeroId(), getRefLogMessage());
		}

		@Override
		protected Result doUpdate(Result desiredResult) throws IOException {
			if(isRefLogIncludingResult()) {
				setRefLogMessage(getRefLogMessage() + ": " + desiredResult.toString(), false);
			}

			if(!addReftable(this::writeUpdate)) {
				return Result.LOCK_FAILURE;
			}

			return desiredResult;
		}

		@Override
		protected Result doDelete(Result desiredResult) throws IOException {

			if(isRefLogIncludingResult()) {
				setRefLogMessage(getRefLogMessage() + ": " + desiredResult.toString(), false);
			}

			if(!addReftable(this::writeDelete)) {
				return Result.LOCK_FAILURE;
			}

			return desiredResult;
		}

		void writeLink(ReftableWriter w) throws IOException {
			long idx = reftableDatabase.nextUpdateIndex();
			w.setMinUpdateIndex(idx).setMaxUpdateIndex(idx).begin()
					.writeRef(dstRef);

			ObjectId beforeId = ObjectId.zeroId();
			Ref before = exactRef(dstRef.getName());
			if(before != null) {
				before = before.getLeaf();
				if(before.getObjectId() != null) {
					beforeId = before.getObjectId();
				}
			}

			Ref after = dstRef.getLeaf();
			ObjectId afterId = ObjectId.zeroId();
			if(after.getObjectId() != null) {
				afterId = after.getObjectId();
			}

			w.writeLog(dstRef.getName(), idx, getRefLogIdent(), beforeId,
					afterId, getRefLogMessage());
		}

		@Override
		protected Result doLink(String target) throws IOException {
			if(isRefLogIncludingResult()) {
				setRefLogMessage(
						getRefLogMessage() + ": " + Result.FORCED,
						false);
			}

			boolean exists = exactRef(getName()) != null;
			dstRef = new SymbolicRef(getName(),
					new ObjectIdRef.Unpeeled(Ref.Storage.NEW, target, null),
					reftableDatabase.nextUpdateIndex());

			if(!addReftable(this::writeLink)) {
				return Result.LOCK_FAILURE;
			}
			return exists ? Result.FORCED : Result.NEW;
		}
	}

}
