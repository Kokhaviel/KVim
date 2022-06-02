/*
 * Copyright (C) 2015, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import org.eclipse.jgit.util.StringUtils;

public class UserAgent {
	private static volatile String userAgent = computeUserAgent();

	private static String computeUserAgent() {
		return clean("JGit/" + computeVersion());
	}

	private static String computeVersion() {
		Package pkg = UserAgent.class.getPackage();
		if(pkg != null) {
			String ver = pkg.getImplementationVersion();
			if(!StringUtils.isEmptyOrNull(ver)) {
				return ver;
			}
		}
		return "unknown";
	}

	static String clean(String s) {
		s = s.trim();
		StringBuilder b = new StringBuilder(s.length());
		for(int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if(c <= 32 || c >= 127) {
				if(b.length() > 0 && b.charAt(b.length() - 1) == '.')
					continue;
				c = '.';
			}
			b.append(c);
		}
		return b.length() > 0 ? b.toString() : null;
	}

	public static String get() {
		return userAgent;
	}

	public static void set(String agent) {
		userAgent = StringUtils.isEmptyOrNull(agent) ? null : clean(agent);
	}

	private UserAgent() {
	}
}
