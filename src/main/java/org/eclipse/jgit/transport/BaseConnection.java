/*
 * Copyright (C) 2010, Google Inc.
 * Copyright (C) 2008, Marek Zawirski <marek.zawirski@gmail.com>
 * Copyright (C) 2008, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import java.io.StringWriter;
import java.io.Writer;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.Ref;

public abstract class BaseConnection implements Connection {

	private Map<String, Ref> advertisedRefs = Collections.emptyMap();
	private String peerUserAgent;
	private boolean startedOperation;
	private Writer messageWriter;

	@Override
	public Map<String, Ref> getRefsMap() {
		return advertisedRefs;
	}

	@Override
	public final Collection<Ref> getRefs() {
		return advertisedRefs.values();
	}

	@Override
	public final Ref getRef(String name) {
		return advertisedRefs.get(name);
	}

	@Override
	public String getMessages() {
		return messageWriter != null ? messageWriter.toString() : "";
	}

	@Override
	public String getPeerUserAgent() {
		return peerUserAgent;
	}

	protected void setPeerUserAgent(String agent) {
		peerUserAgent = agent;
	}

	@Override
	public abstract void close();

	protected void available(Map<String, Ref> all) {
		advertisedRefs = Collections.unmodifiableMap(all);
	}

	protected void markStartedOperation() throws TransportException {
		if(startedOperation)
			throw new TransportException(
					JGitText.get().onlyOneOperationCallPerConnectionIsSupported);
		startedOperation = true;
	}

	protected Writer getMessageWriter() {
		if(messageWriter == null)
			setMessageWriter(new StringWriter());
		return messageWriter;
	}

	protected void setMessageWriter(Writer writer) {
		if(messageWriter != null)
			throw new IllegalStateException(JGitText.get().writerAlreadyInitialized);
		messageWriter = writer;
	}
}
