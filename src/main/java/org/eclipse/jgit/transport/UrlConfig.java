/*
 * Copyright (C) 2022 Thomas Wolf <thomas.wolf@paranor.ch> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.transport;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.eclipse.jgit.lib.Config;

public class UrlConfig {
	private static final String KEY_INSTEADOF = "insteadof";
	private static final String KEY_PUSHINSTEADOF = "pushinsteadof";
	private static final String SECTION_URL = "url";
	private final Config config;
	private Map<String, String> insteadOf;
	private Map<String, String> pushInsteadOf;

	public UrlConfig(Config config) {
		this.config = config;
	}

	public String replace(String url) {
		if(insteadOf == null) {
			insteadOf = load(KEY_INSTEADOF);
		}
		return replace(url, insteadOf);
	}

	public boolean hasPushReplacements() {
		if(pushInsteadOf == null) {
			pushInsteadOf = load(KEY_PUSHINSTEADOF);
		}
		return !pushInsteadOf.isEmpty();
	}

	public String replacePush(String url) {
		if(pushInsteadOf == null) {
			pushInsteadOf = load(KEY_PUSHINSTEADOF);
		}
		return replace(url, pushInsteadOf);
	}

	private Map<String, String> load(String key) {
		Map<String, String> replacements = new HashMap<>();
		for(String url : config.getSubsections(SECTION_URL)) {
			for(String prefix : config.getStringList(SECTION_URL, url, key)) {
				replacements.put(prefix, url);
			}
		}
		return replacements;
	}

	private String replace(String uri, Map<String, String> replacements) {
		Entry<String, String> match = null;
		for(Entry<String, String> replacement : replacements.entrySet()) {
			if(match != null && match.getKey().length() > replacement.getKey()
					.length()) {
				continue;
			}
			if(uri.startsWith(replacement.getKey())) {
				match = replacement;
			}
		}
		if(match != null) {
			return match.getValue() + uri.substring(match.getKey().length());
		}
		return uri;
	}
}
