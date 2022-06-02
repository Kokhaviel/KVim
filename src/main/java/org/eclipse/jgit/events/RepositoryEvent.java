/*
 * Copyright (C) 2010, Google Inc.
 * Copyright (C) 2008, Robin Rosenberg <robin.rosenberg@dewire.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.events;

import org.eclipse.jgit.lib.Repository;

public abstract class RepositoryEvent<T extends RepositoryListener> {
	private Repository repository;

	public void setRepository(Repository r) {
		if (repository == null)
			repository = r;
	}

	public abstract Class<T> getListenerType();

	public abstract void dispatch(T listener);

	@Override
	public String toString() {
		String type = getClass().getSimpleName();
		if (repository == null)
			return type;
		return type + "[" + repository + "]";
	}
}
