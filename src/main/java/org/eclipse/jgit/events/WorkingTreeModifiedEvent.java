/*
 * Copyright (C) 2017, Thomas Wolf <thomas.wolf@paranor.ch> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.events;

import java.util.Collection;

public class WorkingTreeModifiedEvent extends RepositoryEvent<WorkingTreeModifiedListener> {

	private final Collection<String> modified;
	private final Collection<String> deleted;

	public WorkingTreeModifiedEvent(Collection<String> modified,
			Collection<String> deleted) {
		this.modified = modified;
		this.deleted = deleted;
	}

	public boolean isEmpty() {
		return (modified == null || modified.isEmpty()) && (deleted == null || deleted.isEmpty());
	}

	@Override
	public Class<WorkingTreeModifiedListener> getListenerType() {
		return WorkingTreeModifiedListener.class;
	}

	@Override
	public void dispatch(WorkingTreeModifiedListener listener) {
		listener.onWorkingTreeModified(this);
	}
}
