/*
 * Copyright (C) 2015 Obeo. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.hooks;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.Callable;

import org.eclipse.jgit.api.errors.AbortedByHookException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.ProcessResult;
import org.eclipse.jgit.util.SystemReader;
import org.eclipse.jgit.util.io.TeeOutputStream;

public abstract class GitHook<T> implements Callable<T> {

	private final Repository repo;
	private final OutputStream outputStream;
	private final OutputStream errorStream;

	protected GitHook(Repository repo, OutputStream outputStream) {
		this(repo, outputStream, null);
	}

	protected GitHook(Repository repo, OutputStream outputStream, OutputStream errorStream) {
		this.repo = repo;
		this.outputStream = outputStream;
		this.errorStream = errorStream;
	}

	@Override
	public abstract T call() throws IOException, AbortedByHookException;

	public abstract String getHookName();

	protected Repository getRepository() {
		return repo;
	}

	protected String[] getParameters() {
		return new String[0];
	}

	protected String getStdinArgs() {
		return null;
	}

	protected OutputStream getOutputStream() {
		return outputStream == null ? System.out : outputStream;
	}

	protected OutputStream getErrorStream() {
		return errorStream == null ? System.err : errorStream;
	}

	protected void doRun() throws AbortedByHookException, IOException {
		final ByteArrayOutputStream errorByteArray = new ByteArrayOutputStream();
		final TeeOutputStream stderrStream = new TeeOutputStream(errorByteArray, getErrorStream());
		Repository repository = getRepository();
		FS fs = repository.getFS();
		if(fs == null) {
			fs = FS.DETECTED;
		}
		ProcessResult result = fs.runHookIfPresent(repository, getHookName(),
				getParameters(), getOutputStream(), stderrStream, getStdinArgs());
		if(result.isExecutedWithError()) {
			handleError(errorByteArray.toString(SystemReader.getInstance().getDefaultCharset().name()), result);
		}
	}

	protected void handleError(String message, final ProcessResult result) throws AbortedByHookException {
		throw new AbortedByHookException(message, getHookName());
	}

	public boolean isNativeHookPresent() {
		FS fs = getRepository().getFS();
		if(fs == null) {
			fs = FS.DETECTED;
		}
		return fs.findHook(getRepository(), getHookName()) != null;
	}
}
