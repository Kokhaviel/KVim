/*
 * Copyright (C) 2014 Obeo. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.util;

public class ProcessResult {

	public enum Status {
		OK,
		NOT_PRESENT,
		NOT_SUPPORTED
	}

	private final int exitCode;
	private final Status status;

	public ProcessResult(Status status) {
		this(-1, status);
	}

	public ProcessResult(int exitCode, Status status) {
		this.exitCode = exitCode;
		this.status = status;
	}

	public int getExitCode() {
		return exitCode;
	}

	public Status getStatus() {
		return status;
	}

	public boolean isExecutedWithError() {
		return getStatus() == ProcessResult.Status.OK && getExitCode() != 0;
	}
}
