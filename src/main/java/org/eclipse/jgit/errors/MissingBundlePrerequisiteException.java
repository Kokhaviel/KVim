/*
 * Copyright (C) 2008, Google Inc.
 * Copyright (C) 2009, Sasa Zivkov <sasa.zivkov@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.errors;

import java.util.Map;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.URIish;

public class MissingBundlePrerequisiteException extends TransportException {
	private static final long serialVersionUID = 1L;

	private static String format(Map<ObjectId, String> missingCommits) {
		final StringBuilder r = new StringBuilder();
		r.append(JGitText.get().missingPrerequisiteCommits);
		for (Map.Entry<ObjectId, String> e : missingCommits.entrySet()) {
			r.append("\n  ");
			r.append(e.getKey().name());
			if (e.getValue() != null)
				r.append(" ").append(e.getValue());
		}
		return r.toString();
	}

	public MissingBundlePrerequisiteException(final URIish uri,
			final Map<ObjectId, String> missingCommits) {
		super(uri, format(missingCommits));
	}
}
