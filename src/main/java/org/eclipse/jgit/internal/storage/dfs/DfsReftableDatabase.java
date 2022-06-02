/*
 * Copyright (C) 2017, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.dfs;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.internal.storage.reftable.MergedReftable;
import org.eclipse.jgit.internal.storage.reftable.ReftableConfig;
import org.eclipse.jgit.internal.storage.reftable.ReftableDatabase;
import org.eclipse.jgit.lib.BatchRefUpdate;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.util.RefList;
import org.eclipse.jgit.util.RefMap;

public class DfsReftableDatabase extends DfsRefDatabase {
	final ReftableDatabase reftableDatabase;

	private DfsReader ctx;
	private DfsReftableStack stack;

	protected DfsReftableDatabase(DfsRepository repo) {
		super(repo);
		reftableDatabase = new ReftableDatabase() {
			@Override
			public MergedReftable openMergedReftable() throws IOException {
				Lock l = DfsReftableDatabase.this.getLock();
				l.lock();
				try {
					return new MergedReftable(stack().readers());
				} finally {
					l.unlock();
				}
			}
		};
		stack = null;
	}

	@Override
	public boolean hasVersioning() {
		return true;
	}

	@Override
	public boolean performsAtomicTransactions() {
		return true;
	}

	@Override
	public BatchRefUpdate newBatchUpdate() {
		DfsObjDatabase odb = getRepository().getObjectDatabase();
		return new DfsReftableBatchRefUpdate(this, odb);
	}

	public ReftableConfig getReftableConfig() {
		return new ReftableConfig(getRepository());
	}

	protected ReentrantLock getLock() {
		return reftableDatabase.getLock();
	}

	protected boolean compactDuringCommit() {
		return true;
	}

	protected DfsReftableStack stack() throws IOException {
		if (!getLock().isLocked()) {
			throw new IllegalStateException("most hold lock to access stack");
		}
		DfsObjDatabase odb = getRepository().getObjectDatabase();

		if (ctx == null) {
			ctx = odb.newReader();
		}
		if (stack == null) {
			stack = DfsReftableStack.open(ctx, Arrays.asList(odb.getReftables()));
		}
		return stack;
	}

	@Override
	public boolean isNameConflicting(String refName) throws IOException {
		return reftableDatabase.isNameConflicting(refName, new TreeSet<>(), new HashSet<>());
	}

	@Override
	public Ref exactRef(String name) throws IOException {
		return reftableDatabase.exactRef(name);
	}

	@Override
	public Map<String, Ref> getRefs(String prefix) throws IOException {
		List<Ref> refs = reftableDatabase.getRefsByPrefix(prefix);
		RefList.Builder<Ref> builder = new RefList.Builder<>(refs.size());
		for (Ref r : refs) {
			builder.add(r);
		}
		return new RefMap(prefix, builder.toRefList(), RefList.emptyList(),
			RefList.emptyList());
	}

	@Override
	public List<Ref> getRefsByPrefix(String prefix) throws IOException {

		return reftableDatabase.getRefsByPrefix(prefix);
	}

	@Override
	public Set<Ref> getTipsWithSha1(ObjectId id) throws IOException {
		if (!getReftableConfig().isIndexObjects()) {
			return super.getTipsWithSha1(id);
		}
		return reftableDatabase.getTipsWithSha1(id);
	}

	@Override
	public Ref peel(Ref ref) throws IOException {
		Ref oldLeaf = ref.getLeaf();
		if (oldLeaf.isPeeled() || oldLeaf.getObjectId() == null) {
			return ref;
		}
		return recreate(ref, doPeel(oldLeaf), hasVersioning());
	}

	@Override
	boolean exists() throws IOException {
		DfsObjDatabase odb = getRepository().getObjectDatabase();
		return odb.getReftables().length > 0;
	}

	@Override
	void clearCache() {
		ReentrantLock l = getLock();
		l.lock();
		try {
			if (ctx != null) {
				ctx.close();
				ctx = null;
			}
			reftableDatabase.clearCache();
			if (stack != null) {
				stack.close();
				stack = null;
			}
		} finally {
			l.unlock();
		}
	}

	@Override
	protected boolean compareAndPut(Ref oldRef, @Nullable Ref newRef)
			throws IOException {
		ReceiveCommand cmd = ReftableDatabase.toCommand(oldRef, newRef);
		try (RevWalk rw = new RevWalk(getRepository())) {
			rw.setRetainBody(false);
			newBatchUpdate().setAllowNonFastForwards(true).addCommand(cmd)
					.execute(rw, NullProgressMonitor.INSTANCE);
		}
		switch (cmd.getResult()) {
		case OK:
			return true;
		case REJECTED_OTHER_REASON:
			throw new IOException(cmd.getMessage());
		case LOCK_FAILURE:
		default:
			return false;
		}
	}

	@Override
	protected boolean compareAndRemove(Ref oldRef) throws IOException {
		return compareAndPut(oldRef, null);
	}

	@Override
	protected RefCache scanAllRefs() {
		throw new UnsupportedOperationException();
	}

	@Override
	void stored(Ref ref) {
	}

	@Override
	void removed(String refName) {
	}

	@Override
	protected void cachePeeledState(Ref oldLeaf, Ref newLeaf) {
	}

}
