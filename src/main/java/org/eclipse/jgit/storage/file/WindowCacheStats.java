/*
 * Copyright (C) 2018, David Pursehouse <david.pursehouse@gmail.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.storage.file;

import org.eclipse.jgit.internal.storage.file.WindowCache;

import javax.management.MXBean;

@MXBean
public interface WindowCacheStats {
	@Deprecated
	static int getOpenFiles() {
		return (int) WindowCache.getInstance().getStats().getOpenFileCount();
	}

	@Deprecated
	static long getOpenBytes() {
		return WindowCache.getInstance().getStats().getOpenByteCount();
	}

	static WindowCacheStats getStats() {
		return WindowCache.getInstance().getStats();
	}

	long getOpenFileCount();

	long getOpenByteCount();

}
