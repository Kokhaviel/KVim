/*
 * Copyright (C) 2015, 2022 Obeo and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.hooks;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Collection;

import org.eclipse.jgit.api.errors.AbortedByHookException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RemoteRefUpdate;

public class PrePushHook extends GitHook<String> {

	public static final String NAME = "pre-push";
	private String remoteName;
	private String remoteLocation;
	private String refs;

	protected PrePushHook(Repository repo, PrintStream outputStream) {
		super(repo, outputStream);
	}

	@Override
	protected String getStdinArgs() {
		return refs;
	}

	@Override
	public String call() throws IOException, AbortedByHookException {
		if(canRun()) {
			doRun();
		}
		return "";
	}

	private boolean canRun() {
		return true;
	}

	@Override
	public String getHookName() {
		return NAME;
	}

	@Override
	protected String[] getParameters() {
		if(remoteName == null) {
			remoteName = remoteLocation;
		}
		return new String[] {remoteName, remoteLocation};
	}

	public void setRemoteName(String name) {
		remoteName = name;
	}

	public void setRemoteLocation(String location) {
		remoteLocation = location;
	}

	public void setRefs(Collection<RemoteRefUpdate> toRefs) {
		StringBuilder b = new StringBuilder();
		for(RemoteRefUpdate u : toRefs) {
			b.append(u.getSrcRef());
			b.append(" ");
			b.append(u.getNewObjectId().getName());
			b.append(" ");
			b.append(u.getRemoteName());
			b.append(" ");
			ObjectId ooid = u.getExpectedOldObjectId();
			b.append((ooid == null) ? ObjectId.zeroId().getName() : ooid
					.getName());
			b.append("\n");
		}
		refs = b.toString();
	}
}
