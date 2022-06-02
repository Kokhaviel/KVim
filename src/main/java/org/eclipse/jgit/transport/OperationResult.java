/*
 * Copyright (C) 2010, Google Inc.
 * Copyright (C) 2008, Marek Zawirski <marek.zawirski@gmail.com>
 * Copyright (C) 2007-2009, Robin Rosenberg <robin.rosenberg@dewire.com>
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
import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import org.eclipse.jgit.lib.Ref;

public abstract class OperationResult {

	Map<String, Ref> advertisedRefs = Collections.emptyMap();
	URIish uri;
	final SortedMap<String, TrackingRefUpdate> updates = new TreeMap<>();
	StringBuilder messageBuffer;
	String peerUserAgent;

	public Collection<Ref> getAdvertisedRefs() {
		return Collections.unmodifiableCollection(advertisedRefs.values());
	}

	public final Ref getAdvertisedRef(String name) {
		return advertisedRefs.get(name);
	}

	void setAdvertisedRefs(URIish u, Map<String, Ref> ar) {
		uri = u;
		advertisedRefs = ar;
	}

	void add(TrackingRefUpdate u) {
		updates.put(u.getLocalName(), u);
	}

	void addMessages(String msg) {
		if(msg != null && msg.length() > 0) {
			if(messageBuffer == null)
				messageBuffer = new StringBuilder();
			messageBuffer.append(msg);
			if(!msg.endsWith("\n"))
				messageBuffer.append('\n');
		}
	}

}
