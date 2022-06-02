/*
 * Copyright (C) 2010, Christian Halstrick <christian.halstrick@sap.com>,
 * Copyright (C) 2010, Stefan Lay <stefan.lay@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import java.util.List;

import org.eclipse.jgit.errors.UnsupportedCredentialItem;

public abstract class CredentialsProvider {
	private static volatile CredentialsProvider defaultProvider;

	public static CredentialsProvider getDefault() {
		return defaultProvider;
	}
	public static void setDefault(CredentialsProvider p) {
		defaultProvider = p;
	}

	public abstract boolean supports(CredentialItem... items);

	public abstract boolean get(URIish uri, CredentialItem... items)
			throws UnsupportedCredentialItem;

	public boolean get(URIish uri, List<CredentialItem> items)
			throws UnsupportedCredentialItem {
		return get(uri, items.toArray(new CredentialItem[0]));
	}
}
