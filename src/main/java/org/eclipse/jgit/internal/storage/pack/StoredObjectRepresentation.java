/*
 * Copyright (C) 2010, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.pack;

import org.eclipse.jgit.lib.ObjectId;

public class StoredObjectRepresentation {
	public static final int WEIGHT_UNKNOWN = Integer.MAX_VALUE;
	public static final int PACK_DELTA = 0;
	public static final int PACK_WHOLE = 1;
	public static final int FORMAT_OTHER = 2;

	public int getWeight() {
		return WEIGHT_UNKNOWN;
	}

	public int getFormat() {
		return FORMAT_OTHER;
	}

	public ObjectId getDeltaBase() {
		return null;
	}

	public boolean wasDeltaAttempted() {
		int fmt = getFormat();
		return fmt == PACK_DELTA || fmt == PACK_WHOLE;
	}
}
