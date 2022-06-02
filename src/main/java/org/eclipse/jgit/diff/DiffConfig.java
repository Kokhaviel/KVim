/*
 * Copyright (C) 2010, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.diff;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.util.StringUtils;

import java.text.MessageFormat;

public class DiffConfig {

	public static final Config.SectionParser<DiffConfig> KEY = DiffConfig::new;

	public enum RenameDetectionType {
		FALSE,
		TRUE,
		COPY
	}

	private final boolean noPrefix;

	private final RenameDetectionType renameDetectionType;

	private final int renameLimit;

	private DiffConfig(Config rc) {
		noPrefix = rc.getBoolean(ConfigConstants.CONFIG_DIFF_SECTION,
				ConfigConstants.CONFIG_KEY_NOPREFIX, false);
		renameDetectionType = parseRenameDetectionType(rc.getString(
				ConfigConstants.CONFIG_DIFF_SECTION, null, ConfigConstants.CONFIG_KEY_RENAMES));
		renameLimit = rc.getInt(ConfigConstants.CONFIG_DIFF_SECTION,
				ConfigConstants.CONFIG_KEY_RENAMELIMIT, 400);
	}

	public boolean isNoPrefix() {
		return noPrefix;
	}

	public boolean isRenameDetectionEnabled() {
		return renameDetectionType != RenameDetectionType.FALSE;
	}

	public int getRenameLimit() {
		return renameLimit;
	}

	private static RenameDetectionType parseRenameDetectionType(final String renameString) {
		if(renameString == null) return RenameDetectionType.FALSE;
		else if(StringUtils.equalsIgnoreCase(
				ConfigConstants.CONFIG_RENAMELIMIT_COPY, renameString) || StringUtils.equalsIgnoreCase(
				ConfigConstants.CONFIG_RENAMELIMIT_COPIES, renameString))
			return RenameDetectionType.COPY;
		else {
			final Boolean renameBoolean = StringUtils.toBooleanOrNull(renameString);
			if(renameBoolean == null) throw new IllegalArgumentException(MessageFormat.format(
					JGitText.get().enumValueNotSupported2, ConfigConstants.CONFIG_DIFF_SECTION,
					ConfigConstants.CONFIG_KEY_RENAMES, renameString));
			else if(renameBoolean) return RenameDetectionType.TRUE;
			else return RenameDetectionType.FALSE;
		}
	}
}
