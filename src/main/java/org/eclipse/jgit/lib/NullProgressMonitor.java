/*
 * Copyright (C) 2009, Alex Blewitt <alex.blewitt@gmail.com>
 * Copyright (C) 2007, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.lib;

public class NullProgressMonitor implements ProgressMonitor {

	public static final NullProgressMonitor INSTANCE = new NullProgressMonitor();

	private NullProgressMonitor() {
	}

	@Override
	public void start(int totalTasks) {
	}

	@Override
	public void beginTask(String title, int totalWork) {
	}

	@Override
	public void update(int completed) {
	}

	@Override
	public boolean isCancelled() {
		return false;
	}

	@Override
	public void endTask() {
	}
}
