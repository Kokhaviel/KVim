/*
 * Copyright (C) 2008, Mike Ralphson <mike@abacus.co.uk>
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import java.text.MessageFormat;

import org.eclipse.jgit.internal.JGitText;

public enum TagOpt {
	AUTO_FOLLOW(""),
	NO_TAGS("--no-tags"),
	FETCH_TAGS("--tags");

	private final String option;

	TagOpt(String o) {
		option = o;
	}

	public String option() {
		return option;
	}

	public static TagOpt fromOption(String o) {
		if(o == null || o.length() == 0)
			return AUTO_FOLLOW;
		for(TagOpt tagopt : values()) {
			if(tagopt.option().equals(o))
				return tagopt;
		}
		throw new IllegalArgumentException(MessageFormat.format(JGitText.get().invalidTagOption, o));
	}
}
