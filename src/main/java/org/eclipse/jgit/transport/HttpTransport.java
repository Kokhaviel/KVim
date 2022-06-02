/*
 * Copyright (C) 2009, Constantine Plotnikov <constantine.plotnikov@gmail.com>
 * Copyright (C) 2009, JetBrains s.r.o.
 * Copyright (C) 2009, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.http.HttpConnectionFactory;
import org.eclipse.jgit.transport.http.JDKHttpConnectionFactory;

public abstract class HttpTransport extends Transport {
	protected static volatile HttpConnectionFactory connectionFactory = new JDKHttpConnectionFactory();

	public static HttpConnectionFactory getConnectionFactory() {
		return connectionFactory;
	}

	protected HttpTransport(Repository local, URIish uri) {
		super(local, uri);
	}

	protected HttpTransport(URIish uri) {
		super(uri);
	}
}
