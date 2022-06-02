/*
 * Copyright (C) 2011, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.dfs;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.Arrays;

import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.internal.storage.pack.PackExt;

public abstract class DfsStreamKey {

	public static DfsStreamKey of(DfsRepositoryDescription repo, String name,
								  @Nullable PackExt ext) {
		return new ByteArrayDfsStreamKey(repo, name.getBytes(UTF_8), ext);
	}

	final int hash;
	final int packExtPos;

	protected DfsStreamKey(int hash, @Nullable PackExt ext) {
		this.hash = hash * 31;
		this.packExtPos = ext == null ? 0 : ext.getPosition();
	}

	@Override
	public int hashCode() {
		return hash;
	}

	@Override
	public abstract boolean equals(Object o);

	@Override
	public String toString() {
		return String.format("DfsStreamKey[hash=%08x]", hash);
	}

	private static final class ByteArrayDfsStreamKey extends DfsStreamKey {
		private final DfsRepositoryDescription repo;

		private final byte[] name;

		ByteArrayDfsStreamKey(DfsRepositoryDescription repo, byte[] name,
							  @Nullable PackExt ext) {
			super(repo.hashCode() * 31 + Arrays.hashCode(name), ext);
			this.repo = repo;
			this.name = name;
		}

		@Override
		public boolean equals(Object o) {
			if(o instanceof ByteArrayDfsStreamKey) {
				ByteArrayDfsStreamKey k = (ByteArrayDfsStreamKey) o;
				return hash == k.hash && repo.equals(k.repo)
						&& Arrays.equals(name, k.name);
			}
			return false;
		}
	}

	static final class ForReverseIndex extends DfsStreamKey {
		private final DfsStreamKey idxKey;

		ForReverseIndex(DfsStreamKey idxKey) {
			super(idxKey.hash + 1, null);
			this.idxKey = idxKey;
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof ForReverseIndex && idxKey.equals(((ForReverseIndex) o).idxKey);
		}
	}
}
