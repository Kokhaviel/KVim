/*
 * Copyright (C) 2009, Google Inc.
 * Copyright (C) 2009, Yann Simon <yann.simon.fr@gmail.com>
 * Copyright (C) 2011, Matthias Sohn <matthias.sohn@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.lib;

import org.eclipse.jgit.util.SystemReader;

public class UserConfig {
	public static final Config.SectionParser<UserConfig> KEY = UserConfig::new;
	private String committerName;
	private String committerEmail;

	private UserConfig(Config rc) {
		getEmailInternal(rc, Constants.GIT_AUTHOR_EMAIL_KEY);

		committerName = getNameInternal(rc);
		if(committerName == null) {
			committerName = getDefaultUserName();
		}
		committerEmail = getEmailInternal(rc, Constants.GIT_COMMITTER_EMAIL_KEY);
		if(committerEmail == null) {
			committerEmail = getDefaultEmail();
		}
	}

	public String getCommitterName() {
		return committerName;
	}

	public String getCommitterEmail() {
		return committerEmail;
	}

	private static String getNameInternal(Config rc) {
		String username = system().getenv(Constants.GIT_COMMITTER_NAME_KEY);

		if(username == null) {
			username = rc.getString("user", null, "name");
		}

		return stripInvalidCharacters(username);
	}

	private static String getDefaultUserName() {
		String username = system().getProperty(Constants.OS_USER_NAME_KEY);
		if(username == null)
			username = Constants.UNKNOWN_USER_DEFAULT;
		return username;
	}

	private static String getEmailInternal(Config rc, String envKey) {
		String email = system().getenv(envKey);

		if(email == null) {
			email = rc.getString("user", null, "email");
		}

		return stripInvalidCharacters(email);
	}

	private static String stripInvalidCharacters(String s) {
		return s == null ? null : s.replaceAll("<|>|\n", "");
	}

	private static String getDefaultEmail() {
		String username = getDefaultUserName();
		return username + "@" + system().getHostname();
	}

	private static SystemReader system() {
		return SystemReader.getInstance();
	}
}
