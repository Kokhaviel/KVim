/**
 * Copyright (C) 2015, Google Inc. and others
 * <p>
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 * <p>
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.revwalk.filter;

import java.io.IOException;

import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.revwalk.ObjectWalk;

public abstract class ObjectFilter {
	public static final ObjectFilter ALL = new AllFilter();

	private static final class AllFilter extends ObjectFilter {
		@Override
		public boolean include(ObjectWalk walker, AnyObjectId o) {
			return true;
		}
	}

	public abstract boolean include(ObjectWalk walker, AnyObjectId objid) throws IOException;
}
