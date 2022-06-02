/*
 * Copyright (C) 2011, Robin Stocker <robin@nibor.org>
 * Copyright (C) 2012, Matthias Sohn <matthias.sohn@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.lib;

public class BranchConfig {

	public enum BranchRebaseMode implements Config.ConfigEnum {

		REBASE("true"),
		PRESERVE("preserve"),
		INTERACTIVE("interactive"),
		NONE("false");

		private final String configValue;

		BranchRebaseMode(String configValue) {
			this.configValue = configValue;
		}

		@Override
		public String toConfigValue() {
			return configValue;
		}

		@Override
		public boolean matchConfigValue(String s) {
			return configValue.equals(s);
		}
	}

	public static final String LOCAL_REPOSITORY = ".";

	private final Config config;
	private final String branchName;

	public BranchConfig(Config config, String branchName) {
		this.config = config;
		this.branchName = branchName;
	}

	public boolean isRemoteLocal() {
		return LOCAL_REPOSITORY.equals(getRemote());
	}

	public String getRemote() {
		return config.getString(ConfigConstants.CONFIG_BRANCH_SECTION,
				branchName, ConfigConstants.CONFIG_KEY_REMOTE);
	}

	public String getPushRemote() {
		return config.getString(ConfigConstants.CONFIG_BRANCH_SECTION,
				branchName, ConfigConstants.CONFIG_KEY_PUSH_REMOTE);
	}

	public String getMerge() {
		return config.getString(ConfigConstants.CONFIG_BRANCH_SECTION,
				branchName, ConfigConstants.CONFIG_KEY_MERGE);
	}

}
