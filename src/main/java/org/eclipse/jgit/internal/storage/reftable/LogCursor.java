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

import java.io.IOException;

import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.lib.ReflogEntry;

public abstract class LogCursor implements AutoCloseable {

	public abstract boolean next() throws IOException;

	public abstract String getRefName();

	public abstract long getUpdateIndex();

	@Nullable
	public abstract ReflogEntry getReflogEntry();

	@Override
	public abstract void close();
}
