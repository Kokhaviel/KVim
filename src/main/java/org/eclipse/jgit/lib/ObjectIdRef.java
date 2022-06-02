/*
 * Copyright (C) 2010, Google Inc.
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.lib;

import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.annotations.Nullable;

public abstract class ObjectIdRef implements Ref {

	public static class Unpeeled extends ObjectIdRef {

		public Unpeeled(@NonNull Storage st, @NonNull String name,
						@Nullable ObjectId id) {
			super(st, name, id, UNDEFINED_UPDATE_INDEX);
		}

		public Unpeeled(@NonNull Storage st, @NonNull String name,
						@Nullable ObjectId id, long updateIndex) {
			super(st, name, id, updateIndex);
		}

		@Override
		@Nullable
		public ObjectId getPeeledObjectId() {
			return null;
		}

		@Override
		public boolean isPeeled() {
			return false;
		}
	}

	public static class PeeledTag extends ObjectIdRef {
		private final ObjectId peeledObjectId;

		public PeeledTag(@NonNull Storage st, @NonNull String name,
						 @Nullable ObjectId id, @NonNull ObjectId p) {
			super(st, name, id, UNDEFINED_UPDATE_INDEX);
			peeledObjectId = p;
		}

		public PeeledTag(@NonNull Storage st, @NonNull String name,
						 @Nullable ObjectId id, @NonNull ObjectId p, long updateIndex) {
			super(st, name, id, updateIndex);
			peeledObjectId = p;
		}

		@Override
		@NonNull
		public ObjectId getPeeledObjectId() {
			return peeledObjectId;
		}

		@Override
		public boolean isPeeled() {
			return true;
		}
	}

	public static class PeeledNonTag extends ObjectIdRef {

		public PeeledNonTag(@NonNull Storage st, @NonNull String name,
							@Nullable ObjectId id) {
			super(st, name, id, UNDEFINED_UPDATE_INDEX);
		}

		public PeeledNonTag(@NonNull Storage st, @NonNull String name,
							@Nullable ObjectId id, long updateIndex) {
			super(st, name, id, updateIndex);
		}

		@Override
		@Nullable
		public ObjectId getPeeledObjectId() {
			return null;
		}

		@Override
		public boolean isPeeled() {
			return true;
		}
	}

	private final String name;
	private final Storage storage;
	private final ObjectId objectId;
	private final long updateIndex;

	protected ObjectIdRef(@NonNull Storage st, @NonNull String name,
						  @Nullable ObjectId id, long updateIndex) {
		this.name = name;
		this.storage = st;
		this.objectId = id;
		this.updateIndex = updateIndex;
	}

	@Override
	@NonNull
	public String getName() {
		return name;
	}

	@Override
	public boolean isSymbolic() {
		return false;
	}

	@Override
	@NonNull
	public Ref getLeaf() {
		return this;
	}

	@Override
	@NonNull
	public Ref getTarget() {
		return this;
	}

	@Override
	@Nullable
	public ObjectId getObjectId() {
		return objectId;
	}

	@Override
	@NonNull
	public Storage getStorage() {
		return storage;
	}

	@Override
	public long getUpdateIndex() {
		if(updateIndex == UNDEFINED_UPDATE_INDEX) {
			throw new UnsupportedOperationException();
		}
		return updateIndex;
	}

	@NonNull
	@Override
	public String toString() {
		return "Ref[" + getName() + '=' + ObjectId.toString(getObjectId()) + '(' + updateIndex + ")]";
	}
}
