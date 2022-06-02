/*
 * Copyright (C) 2017, Google LLC and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.reftable;

import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefDatabase;
import org.eclipse.jgit.lib.ReflogReader;
import org.eclipse.jgit.transport.ReceiveCommand;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

public abstract class ReftableDatabase {

	private final ReentrantLock lock = new ReentrantLock(true);
	private Reftable mergedTables;

	protected abstract MergedReftable openMergedReftable() throws IOException;

	public long nextUpdateIndex() throws IOException {
		lock.lock();
		try {
			return reader().maxUpdateIndex() + 1;
		} finally {
			lock.unlock();
		}
	}

	public ReflogReader getReflogReader(String refname) throws IOException {
		lock.lock();
		try {
			return new ReftableReflogReader(lock, reader(), refname);
		} finally {
			lock.unlock();
		}
	}

	public static ReceiveCommand toCommand(Ref oldRef, Ref newRef) {
		ObjectId oldId = toId(oldRef);
		ObjectId newId = toId(newRef);
		String name = oldRef != null ? oldRef.getName() : newRef.getName();

		if(oldRef != null && oldRef.isSymbolic()) {
			if(newRef != null) {
				if(newRef.isSymbolic()) {
					return ReceiveCommand.link(oldRef.getTarget().getName(),
							newRef.getTarget().getName(), name);
				}

				return ReceiveCommand.unlink(oldRef.getTarget().getName(),
						newId, name);
			}
			return ReceiveCommand.unlink(oldRef.getTarget().getName(),
					ObjectId.zeroId(), name);
		}

		if(newRef != null && newRef.isSymbolic()) {
			if(oldRef != null) {
				if(oldRef.isSymbolic()) {
					return ReceiveCommand.link(oldRef.getTarget().getName(),
							newRef.getTarget().getName(), name);
				}
				return ReceiveCommand.link(oldId,
						newRef.getTarget().getName(), name);
			}
			return ReceiveCommand.link(ObjectId.zeroId(),
					newRef.getTarget().getName(), name);
		}

		return new ReceiveCommand(oldId, newId, name);
	}

	private static ObjectId toId(Ref ref) {
		if(ref != null) {
			ObjectId id = ref.getObjectId();
			if(id != null) {
				return id;
			}
		}
		return ObjectId.zeroId();
	}

	public ReentrantLock getLock() {
		return lock;
	}

	private Reftable reader() throws IOException {
		if(!lock.isLocked()) {
			throw new IllegalStateException(
					"must hold lock to access merged table");
		}
		if(mergedTables == null) {
			mergedTables = openMergedReftable();
		}
		return mergedTables;
	}

	public boolean isNameConflicting(String refName, TreeSet<String> added,
									 Set<String> deleted) throws IOException {
		lock.lock();
		try {
			Reftable table = reader();

			int lastSlash = refName.lastIndexOf('/');
			while(0 < lastSlash) {
				String prefix = refName.substring(0, lastSlash);
				if(!deleted.contains(prefix)
						&& (table.hasRef(prefix) || added.contains(prefix))) {
					return true;
				}
				lastSlash = refName.lastIndexOf('/', lastSlash - 1);
			}

			String prefix = refName + '/';
			RefCursor c = table.seekRefsWithPrefix(prefix);
			while(c.next()) {
				if(!deleted.contains(c.getRef().getName())) {
					return true;
				}
			}

			String it = added.ceiling(refName + '/');
			return it != null && it.startsWith(prefix);
		} finally {
			lock.unlock();
		}
	}

	@Nullable
	public Ref exactRef(String name) throws IOException {
		lock.lock();
		try {
			Reftable table = reader();
			Ref ref = table.exactRef(name);
			if(ref != null && ref.isSymbolic()) {
				return table.resolve(ref);
			}
			return ref;
		} finally {
			lock.unlock();
		}
	}

	public List<Ref> getRefsByPrefix(String prefix) throws IOException {
		List<Ref> all = new ArrayList<>();
		lock.lock();
		try {
			Reftable table = reader();
			try(RefCursor rc = RefDatabase.ALL.equals(prefix) ? table.allRefs()
					: table.seekRefsWithPrefix(prefix)) {
				while(rc.next()) {
					Ref ref = table.resolve(rc.getRef());
					if(ref != null && ref.getObjectId() != null) {
						all.add(ref);
					}
				}
			}
		} finally {
			lock.unlock();
		}

		return Collections.unmodifiableList(all);
	}

	public Set<Ref> getTipsWithSha1(ObjectId id) throws IOException {
		lock.lock();
		try {
			RefCursor cursor = reader().byObjectId(id);
			Set<Ref> refs = new HashSet<>();
			while(cursor.next()) {
				refs.add(cursor.getRef());
			}
			return refs;
		} finally {
			lock.unlock();
		}
	}

	public void clearCache() {
		lock.lock();
		try {
			mergedTables = null;
		} finally {
			lock.unlock();
		}
	}
}
