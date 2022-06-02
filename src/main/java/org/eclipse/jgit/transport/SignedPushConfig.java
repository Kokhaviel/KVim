/*
 * Copyright (C) 2015, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Config.SectionParser;

public class SignedPushConfig {
	public static final SectionParser<SignedPushConfig> KEY =
			SignedPushConfig::new;

	private String certNonceSeed;
	private final int certNonceSlopLimit;
	private NonceGenerator nonceGenerator;

	SignedPushConfig(Config cfg) {
		setCertNonceSeed(cfg.getString("receive", null, "certnonceseed")); 
		certNonceSlopLimit = cfg.getInt("receive", "certnonceslop", 0); 
	}

	public void setCertNonceSeed(String seed) {
		certNonceSeed = seed;
	}

	public int getCertNonceSlopLimit() {
		return certNonceSlopLimit;
	}

	public NonceGenerator getNonceGenerator() {
		if (nonceGenerator != null) {
			return nonceGenerator;
		} else if (certNonceSeed != null) {
			return new HMACSHA1NonceGenerator(certNonceSeed);
		}
		return null;
	}
}
