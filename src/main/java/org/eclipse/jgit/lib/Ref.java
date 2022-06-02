/*
 * Copyright (C) 2006-2008, Shawn O. Pearce <spearce@spearce.org> and others
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

public interface Ref {
	enum Storage {

		NEW(true, false),
		LOOSE(true, false),
		PACKED(false, true),
		LOOSE_PACKED(true, true),
		NETWORK(false, false);

		private final boolean loose;

		private final boolean packed;

		Storage(boolean l, boolean p) {
			loose = l;
			packed = p;
		}

		public boolean isLoose() {
			return loose;
		}

		public boolean isPacked() {
			return packed;
		}
	}

	long UNDEFINED_UPDATE_INDEX = -1L;

	@NonNull
	String getName();

	boolean isSymbolic();

	@NonNull
	Ref getLeaf();

	@NonNull
	Ref getTarget();

	@Nullable
	ObjectId getObjectId();

	@Nullable
	ObjectId getPeeledObjectId();

	boolean isPeeled();

	@NonNull
	Storage getStorage();

	default long getUpdateIndex() {
		throw new UnsupportedOperationException();
	}
}
