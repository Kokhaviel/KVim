/*
 * Copyright (C) 2017, 2022 David Pursehouse <david.pursehouse@gmail.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import java.util.Locale;

import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.util.StringUtils;

public class PushConfig {

	public enum PushRecurseSubmodulesMode implements Config.ConfigEnum {
		CHECK("check"),
		ON_DEMAND("on-demand"),
		NO("false");

		private final String configValue;

		PushRecurseSubmodulesMode(String configValue) {
			this.configValue = configValue;
		}

		@Override
		public String toConfigValue() {
			return configValue;
		}

		@Override
		public boolean matchConfigValue(String s) {
			if(StringUtils.isEmptyOrNull(s)) {
				return false;
			}
			s = s.replace('-', '_');
			return name().equalsIgnoreCase(s)
					|| configValue.equalsIgnoreCase(s);
		}
	}

	public enum PushDefault implements Config.ConfigEnum {

		NOTHING,
		CURRENT,
		UPSTREAM("tracking"),
		SIMPLE,
		MATCHING;

		private final String alias;

		PushDefault() {
			alias = null;
		}

		PushDefault(String alias) {
			this.alias = alias;
		}

		@Override
		public String toConfigValue() {
			return name().toLowerCase(Locale.ROOT);
		}

		@Override
		public boolean matchConfigValue(String in) {
			return toConfigValue().equalsIgnoreCase(in)
					|| (alias != null && alias.equalsIgnoreCase(in));
		}
	}

	private final PushDefault pushDefault;

	public PushConfig(Config config) {
		config.getEnum(ConfigConstants.CONFIG_PUSH_SECTION,
				null, ConfigConstants.CONFIG_KEY_RECURSE_SUBMODULES,
				PushRecurseSubmodulesMode.NO);
		pushDefault = config.getEnum(ConfigConstants.CONFIG_PUSH_SECTION, null,
				ConfigConstants.CONFIG_KEY_DEFAULT, PushDefault.SIMPLE);
	}

	public PushDefault getPushDefault() {
		return pushDefault;
	}
}
