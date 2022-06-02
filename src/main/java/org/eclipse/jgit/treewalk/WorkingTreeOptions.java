/*
 * Copyright (C) 2010, Marc Strapetz <marc.strapetz@syntevo.com>
 * Copyright (C) 2012-2013, Robin Rosenberg and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.treewalk;

import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.CoreConfig.AutoCRLF;
import org.eclipse.jgit.lib.CoreConfig.CheckStat;
import org.eclipse.jgit.lib.CoreConfig.EOL;
import org.eclipse.jgit.lib.CoreConfig.SymLinks;

public class WorkingTreeOptions {
	public static final Config.SectionParser<WorkingTreeOptions> KEY = WorkingTreeOptions::new;

	private final boolean fileMode;
	private final AutoCRLF autoCRLF;
	private final EOL eol;
	private final CheckStat checkStat;
	private final SymLinks symlinks;
	private final boolean dirNoGitLinks;

	private WorkingTreeOptions(Config rc) {
		fileMode = rc.getBoolean(ConfigConstants.CONFIG_CORE_SECTION,
				ConfigConstants.CONFIG_KEY_FILEMODE, true);
		autoCRLF = rc.getEnum(ConfigConstants.CONFIG_CORE_SECTION, null,
				ConfigConstants.CONFIG_KEY_AUTOCRLF, AutoCRLF.FALSE);
		eol = rc.getEnum(ConfigConstants.CONFIG_CORE_SECTION, null,
				ConfigConstants.CONFIG_KEY_EOL, EOL.NATIVE);
		checkStat = rc.getEnum(ConfigConstants.CONFIG_CORE_SECTION, null,
				ConfigConstants.CONFIG_KEY_CHECKSTAT, CheckStat.DEFAULT);
		symlinks = rc.getEnum(ConfigConstants.CONFIG_CORE_SECTION, null,
				ConfigConstants.CONFIG_KEY_SYMLINKS, SymLinks.TRUE);
		dirNoGitLinks = rc.getBoolean(ConfigConstants.CONFIG_CORE_SECTION, null,
				ConfigConstants.CONFIG_KEY_DIRNOGITLINKS,
				false);
	}

	public boolean isFileMode() {
		return fileMode;
	}

	public AutoCRLF getAutoCRLF() {
		return autoCRLF;
	}

	public EOL getEOL() {
		return eol;
	}

	public CheckStat getCheckStat() {
		return checkStat;
	}

	public SymLinks getSymLinks() {
		return symlinks;
	}

	public boolean isDirNoGitLinks() {
		return dirNoGitLinks;
	}
}
