/*
 * Copyright (C) 2018, 2021 Salesforce and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.lib;

public class GpgConfig {

	public enum GpgFormat implements Config.ConfigEnum {

		OPENPGP("openpgp"),
		X509("x509");

		private final String configValue;

		GpgFormat(String configValue) {
			this.configValue = configValue;
		}

		@Override
		public boolean matchConfigValue(String s) {
			return configValue.equals(s);
		}

		@Override
		public String toConfigValue() {
			return configValue;
		}
	}

	private final GpgFormat keyFormat;
	private final String signingKey;
	private final String program;
	private final boolean signCommits;
	private final boolean signAllTags;
	private final boolean forceAnnotated;

	public GpgConfig(Config config) {
		keyFormat = config.getEnum(GpgFormat.values(),
				ConfigConstants.CONFIG_GPG_SECTION, null,
				ConfigConstants.CONFIG_KEY_FORMAT, GpgFormat.OPENPGP);
		signingKey = config.getString(ConfigConstants.CONFIG_USER_SECTION, null,
				ConfigConstants.CONFIG_KEY_SIGNINGKEY);

		String exe = config.getString(ConfigConstants.CONFIG_GPG_SECTION,
				keyFormat.toConfigValue(), ConfigConstants.CONFIG_KEY_PROGRAM);
		if(exe == null) {
			exe = config.getString(ConfigConstants.CONFIG_GPG_SECTION, null,
					ConfigConstants.CONFIG_KEY_PROGRAM);
		}
		program = exe;
		signCommits = config.getBoolean(ConfigConstants.CONFIG_COMMIT_SECTION,
				ConfigConstants.CONFIG_KEY_GPGSIGN, false);
		signAllTags = config.getBoolean(ConfigConstants.CONFIG_TAG_SECTION,
				ConfigConstants.CONFIG_KEY_GPGSIGN, false);
		forceAnnotated = config.getBoolean(ConfigConstants.CONFIG_TAG_SECTION,
				ConfigConstants.CONFIG_KEY_FORCE_SIGN_ANNOTATED, false);
	}

	public GpgFormat getKeyFormat() {
		return keyFormat;
	}

	public String getProgram() {
		return program;
	}

	public String getSigningKey() {
		return signingKey;
	}

	public boolean isSignCommits() {
		return signCommits;
	}

	public boolean isSignAllTags() {
		return signAllTags;
	}

	public boolean isSignAnnotated() {
		return forceAnnotated;
	}
}
