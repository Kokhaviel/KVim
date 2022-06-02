/*
 * Copyright (C) 2009, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.lib;

import java.io.IOException;

public abstract class ObjectDatabase implements AutoCloseable {

	protected ObjectDatabase() {

	}

	public boolean exists() {
		return true;
	}

	public void create() throws IOException {
	}

	public abstract ObjectInserter newInserter();

	public abstract ObjectReader newReader();

	@Override
	public abstract void close();

	public boolean has(AnyObjectId objectId) throws IOException {
		try(ObjectReader or = newReader()) {
			return or.has(objectId);
		}
	}

	public ObjectLoader open(AnyObjectId objectId)
			throws IOException {
		return open(objectId, ObjectReader.OBJ_ANY);
	}

	public ObjectLoader open(AnyObjectId objectId, int typeHint) throws IOException {
		try(ObjectReader or = newReader()) {
			return or.open(objectId, typeHint);
		}
	}

	public ObjectDatabase newCachedDatabase() {
		return this;
	}

}
