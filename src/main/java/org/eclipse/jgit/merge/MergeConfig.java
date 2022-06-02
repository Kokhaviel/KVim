/*******************************************************************************
 * Copyright (c) 2014 Konrad Kügler and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 *******************************************************************************/
package org.eclipse.jgit.merge;

import java.io.IOException;

import org.eclipse.jgit.api.MergeCommand.FastForwardMode;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Config.SectionParser;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Repository;

public class MergeConfig {

	public static MergeConfig getConfigForCurrentBranch(Repository repo) {
		try {
			String branch = repo.getBranch();
			if(branch != null)
				return repo.getConfig().get(getParser(branch));
		} catch(IOException ignored) {
		}
		return new MergeConfig();
	}

	public static SectionParser<MergeConfig> getParser(
			final String branch) {
		return new MergeConfigSectionParser(branch);
	}

	private final FastForwardMode fastForwardMode;

	private final boolean squash;

	private final boolean commit;

	private MergeConfig(String branch, Config config) {
		String[] mergeOptions = getMergeOptions(branch, config);
		fastForwardMode = getFastForwardMode(config, mergeOptions);
		squash = isMergeConfigOptionSet("--squash", mergeOptions);
		commit = !isMergeConfigOptionSet("--no-commit", mergeOptions);
	}

	private MergeConfig() {
		fastForwardMode = FastForwardMode.FF;
		squash = false;
		commit = true;
	}

	public FastForwardMode getFastForwardMode() {
		return fastForwardMode;
	}

	public boolean isSquash() {
		return squash;
	}

	public boolean isCommit() {
		return commit;
	}

	private static FastForwardMode getFastForwardMode(Config config,
													  String[] mergeOptions) {
		for(String option : mergeOptions) {
			for(FastForwardMode mode : FastForwardMode.values())
				if(mode.matchConfigValue(option))
					return mode;
		}
		return FastForwardMode.valueOf(config.getEnum(
				ConfigConstants.CONFIG_KEY_MERGE, null,
				ConfigConstants.CONFIG_KEY_FF, FastForwardMode.Merge.TRUE));
	}

	private static boolean isMergeConfigOptionSet(String optionToLookFor,
												  String[] mergeOptions) {
		for(String option : mergeOptions) {
			if(optionToLookFor.equals(option))
				return true;
		}
		return false;
	}

	private static String[] getMergeOptions(String branch, Config config) {
		String mergeOptions = config.getString(
				ConfigConstants.CONFIG_BRANCH_SECTION, branch,
				ConfigConstants.CONFIG_KEY_MERGEOPTIONS);
		if(mergeOptions != null) {
			return mergeOptions.split("\\s");
		}
		return new String[0];
	}

	private static class MergeConfigSectionParser implements
			SectionParser<MergeConfig> {

		private final String branch;

		public MergeConfigSectionParser(String branch) {
			this.branch = branch;
		}

		@Override
		public MergeConfig parse(Config cfg) {
			return new MergeConfig(branch, cfg);
		}

		@Override
		public boolean equals(Object obj) {
			if(obj instanceof MergeConfigSectionParser) {
				return branch.equals(((MergeConfigSectionParser) obj).branch);
			}
			return false;
		}

		@Override
		public int hashCode() {
			return branch.hashCode();
		}

	}

}
