/*
 * Copyright (C) 2017, 2020 Thomas Wolf <thomas.wolf@paranor.ch> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.lib;

import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.util.FS;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

public interface TypedConfigGetter {

	boolean getBoolean(Config config, String section, String subsection,
					   String name, boolean defaultValue);

	<T extends Enum<?>> T getEnum(Config config, T[] all, String section,
								  String subsection, String name, T defaultValue);

	int getInt(Config config, String section, String subsection, String name, int defaultValue);

	long getLong(Config config, String section, String subsection, String name, long defaultValue);

	long getTimeUnit(Config config, String section, String subsection,
					 String name, long defaultValue, TimeUnit wantUnit);

	default Path getPath(Config config, String section, String subsection,
						 String name, @NonNull FS fs, File resolveAgainst,
						 Path defaultValue) {
		String value = config.getString(section, subsection, name);
		if(value == null) {
			return defaultValue;
		}
		File file;
		if(value.startsWith("~/")) {
			file = fs.resolve(fs.userHome(), value.substring(2));
		} else {
			file = fs.resolve(resolveAgainst, value);
		}
		return file.toPath();
	}

	@NonNull
	List<RefSpec> getRefSpecs(Config config, String section, String subsection,
							  String name);
}
