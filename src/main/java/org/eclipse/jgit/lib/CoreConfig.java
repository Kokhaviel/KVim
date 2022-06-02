/*
 * Copyright (C) 2013, Gunnar Wagenknecht
 * Copyright (C) 2010, Chris Aniszczyk <caniszczyk@gmail.com>
 * Copyright (C) 2009, Christian Halstrick <christian.halstrick@sap.com>
 * Copyright (C) 2009, Google Inc.
 * Copyright (C) 2007, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2006-2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.lib;

import static java.util.zip.Deflater.DEFAULT_COMPRESSION;

public class CoreConfig {

	public static final Config.SectionParser<CoreConfig> KEY = CoreConfig::new;

	public enum AutoCRLF {
		FALSE,
		TRUE,
		INPUT
	}

	public enum EOL {
		CRLF,
		LF,
		NATIVE
	}

	public enum EolStreamType {
		TEXT_CRLF,
		TEXT_LF,
		AUTO_CRLF,
		AUTO_LF,
		DIRECT
	}

	public enum CheckStat {
		MINIMAL,
		DEFAULT
	}

	public enum LogRefUpdates {
		FALSE,
		TRUE,
		ALWAYS
	}

	private final int compression;
	private final int packIndexVersion;
	private final LogRefUpdates logAllRefUpdates;
	private final String attributesfile;

	public enum SymLinks {
		FALSE,
		TRUE
	}

	public enum HideDotFiles {
		FALSE,
		TRUE,
		DOTGITONLY
	}

	private CoreConfig(Config rc) {
		compression = rc.getInt(ConfigConstants.CONFIG_CORE_SECTION,
				ConfigConstants.CONFIG_KEY_COMPRESSION, DEFAULT_COMPRESSION);
		packIndexVersion = rc.getInt(ConfigConstants.CONFIG_PACK_SECTION,
				ConfigConstants.CONFIG_KEY_INDEXVERSION, 2);
		logAllRefUpdates = rc.getEnum(ConfigConstants.CONFIG_CORE_SECTION, null,
				ConfigConstants.CONFIG_KEY_LOGALLREFUPDATES,
				LogRefUpdates.TRUE);
		attributesfile = rc.getString(ConfigConstants.CONFIG_CORE_SECTION,
				null, ConfigConstants.CONFIG_KEY_ATTRIBUTESFILE);
	}

	public int getCompression() {
		return compression;
	}

	public int getPackIndexVersion() {
		return packIndexVersion;
	}

	@Deprecated
	public boolean isLogAllRefUpdates() {
		return !LogRefUpdates.FALSE.equals(logAllRefUpdates);
	}

	public String getAttributesFile() {
		return attributesfile;
	}
}
