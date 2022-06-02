/*
 * Copyright (C) 2018, Google LLC. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.internal.submodule;

import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_PATH;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_URL;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_SUBMODULE_SECTION;

import java.text.MessageFormat;

import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.Config;

public class SubmoduleValidator {

	public static class SubmoduleValidationException extends Exception {

		private static final long serialVersionUID = 1L;

		SubmoduleValidationException(String message) {
			super(message);
		}
	}

	public static void assertValidSubmoduleName(String name)
			throws SubmoduleValidationException {
		if(name.contains("/../") || name.contains("\\..\\")
				|| name.startsWith("../") || name.startsWith("..\\")
				|| name.endsWith("/..") || name.endsWith("\\..")) {
			throw new SubmoduleValidationException(MessageFormat
					.format(JGitText.get().invalidNameContainsDotDot, name)
			);
		}

		if(name.startsWith("-")) {
			throw new SubmoduleValidationException(MessageFormat.format(
					JGitText.get().submoduleNameInvalid, name));
		}
	}

	public static void assertValidSubmoduleUri(String uri)
			throws SubmoduleValidationException {
		if(uri.startsWith("-")) {
			throw new SubmoduleValidationException(
					MessageFormat.format(
							JGitText.get().submoduleUrlInvalid, uri)
			);
		}
	}

	public static void assertValidSubmodulePath(String path)
			throws SubmoduleValidationException {
		if(path.startsWith("-")) {
			throw new SubmoduleValidationException(
					MessageFormat.format(
							JGitText.get().submodulePathInvalid, path)
			);
		}
	}

	public static void assertValidGitModulesFile(String gitModulesContents)
			throws SubmoduleValidationException {
		Config c = new Config();
		try {
			c.fromText(gitModulesContents);
			for(String subsection :
					c.getSubsections(CONFIG_SUBMODULE_SECTION)) {
				assertValidSubmoduleName(subsection);

				String url = c.getString(
						CONFIG_SUBMODULE_SECTION, subsection, CONFIG_KEY_URL);
				if(url != null) {
					assertValidSubmoduleUri(url);
				}

				String path = c.getString(
						CONFIG_SUBMODULE_SECTION, subsection, CONFIG_KEY_PATH);
				if(path != null) {
					assertValidSubmodulePath(path);
				}
			}
		} catch(ConfigInvalidException e) {
			SubmoduleValidationException sve = new SubmoduleValidationException(
					JGitText.get().invalidGitModules);
			sve.initCause(e);
			throw sve;
		}
	}
}
