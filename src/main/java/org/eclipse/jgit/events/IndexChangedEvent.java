/*
 * Copyright (C) 2010, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.events;

public class IndexChangedEvent extends RepositoryEvent<IndexChangedListener> {

	public IndexChangedEvent() {
	}

	@Override
	public Class<IndexChangedListener> getListenerType() {
		return IndexChangedListener.class;
	}

	@Override
	public void dispatch(IndexChangedListener listener) {
		listener.onIndexChanged(this);
	}
}
