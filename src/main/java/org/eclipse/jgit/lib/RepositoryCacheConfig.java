/*
 * Copyright (C) 2016 Ericsson and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.lib;

import java.util.concurrent.TimeUnit;

public class RepositoryCacheConfig {

	public static final long NO_CLEANUP = 0;
	public static final long AUTO_CLEANUP_DELAY = -1;
	private final long expireAfterMillis;
	private final long cleanupDelayMillis;

	public RepositoryCacheConfig() {
		expireAfterMillis = TimeUnit.HOURS.toMillis(1);
		cleanupDelayMillis = AUTO_CLEANUP_DELAY;
	}

	public long getExpireAfter() {
		return expireAfterMillis;
	}

	public long getCleanupDelay() {
		if(cleanupDelayMillis < 0) {
			return Math.min(expireAfterMillis / 10,
					TimeUnit.MINUTES.toMillis(10));
		}
		return cleanupDelayMillis;
	}

}
