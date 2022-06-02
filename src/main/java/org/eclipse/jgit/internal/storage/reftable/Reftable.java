/*
 * Copyright (C) 2017, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.reftable;

import static org.eclipse.jgit.lib.RefDatabase.MAX_SYMBOLIC_REF_DEPTH;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collection;

import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.internal.storage.io.BlockSource;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.SymbolicRef;

public abstract class Reftable {

	public static Reftable from(Collection<Ref> refs) {
		try {
			ReftableConfig cfg = new ReftableConfig();
			cfg.setIndexObjects(false);
			cfg.setAlignBlocks(false);
			ByteArrayOutputStream buf = new ByteArrayOutputStream();
			new ReftableWriter(buf)
					.setConfig(cfg)
					.begin()
					.sortAndWriteRefs(refs)
					.finish();
			return new ReftableReader(BlockSource.from(buf.toByteArray()));
		} catch(IOException e) {
			throw new RuntimeException(e);
		}
	}

	protected boolean includeDeletes;

	public void setIncludeDeletes(boolean deletes) {
		includeDeletes = deletes;
	}

	public abstract long maxUpdateIndex() throws IOException;

	public abstract long minUpdateIndex() throws IOException;

	public abstract RefCursor allRefs() throws IOException;

	public abstract RefCursor seekRef(String refName) throws IOException;

	public abstract RefCursor seekRefsWithPrefix(String prefix) throws IOException;

	public abstract RefCursor byObjectId(AnyObjectId id) throws IOException;

	public abstract boolean hasObjectMap() throws IOException;

	public abstract LogCursor allLogs() throws IOException;

	public LogCursor seekLog(String refName) throws IOException {
		return seekLog(refName, Long.MAX_VALUE);
	}

	public abstract LogCursor seekLog(String refName, long updateIndex)
			throws IOException;

	@Nullable
	public Ref exactRef(String refName) throws IOException {
		try(RefCursor rc = seekRef(refName)) {
			return rc.next() ? rc.getRef() : null;
		}
	}

	public boolean hasRef(String refName) throws IOException {
		try(RefCursor rc = seekRef(refName)) {
			return rc.next();
		}
	}

	@Nullable
	public Ref resolve(Ref symref) throws IOException {
		return resolve(symref, 0);
	}

	private Ref resolve(Ref ref, int depth) throws IOException {
		if(!ref.isSymbolic()) {
			return ref;
		}

		Ref dst = ref.getTarget();
		if(MAX_SYMBOLIC_REF_DEPTH <= depth) {
			return null;
		}

		dst = exactRef(dst.getName());
		if(dst == null) {
			return ref;
		}

		dst = resolve(dst, depth + 1);
		if(dst == null) {
			return null;
		}
		return new SymbolicRef(ref.getName(), dst, ref.getUpdateIndex());
	}
}
