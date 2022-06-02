/*
 * Copyright (C) 2011, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.pack;

import java.io.IOException;

public abstract class CachedPack {

	public abstract long getObjectCount() throws IOException;

	public long getDeltaCount() {
		return 0;
	}

	public abstract boolean hasObject(ObjectToPack obj, StoredObjectRepresentation rep);
}
