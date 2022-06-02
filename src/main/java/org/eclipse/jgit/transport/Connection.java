/*
 * Copyright (C) 2010, Google Inc.
 * Copyright (C) 2008, Marek Zawirski <marek.zawirski@gmail.com>
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import java.util.Collection;
import java.util.Map;

import org.eclipse.jgit.lib.Ref;

public interface Connection extends AutoCloseable {

	Map<String, Ref> getRefsMap();

	Collection<Ref> getRefs();

	Ref getRef(String name);

	@Override
	void close();

	String getMessages();

	String getPeerUserAgent();
}
