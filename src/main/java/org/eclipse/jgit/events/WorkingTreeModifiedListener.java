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

public interface WorkingTreeModifiedListener extends RepositoryListener {

	void onWorkingTreeModified(WorkingTreeModifiedEvent event);
}
