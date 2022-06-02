/*
 * Copyright (C) 2010, 2013, Mathias Kinzler <mathias.kinzler@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.api;

public class RebaseResult {
	public enum Status {
		OK,
		ABORTED,
		STOPPED,
		EDIT,
		FAILED,
		UNCOMMITTED_CHANGES,
		CONFLICTS,
		UP_TO_DATE,
		FAST_FORWARD,
		NOTHING_TO_COMMIT,
		INTERACTIVE_PREPARED,
		STASH_APPLY_CONFLICTS
	}

	static final RebaseResult OK_RESULT = new RebaseResult(Status.OK);
	static final RebaseResult ABORTED_RESULT = new RebaseResult(Status.ABORTED);
	static final RebaseResult UP_TO_DATE_RESULT = new RebaseResult(Status.UP_TO_DATE);
	static final RebaseResult FAST_FORWARD_RESULT = new RebaseResult(Status.FAST_FORWARD);
	static final RebaseResult NOTHING_TO_COMMIT_RESULT = new RebaseResult(Status.NOTHING_TO_COMMIT);
	static final RebaseResult STASH_APPLY_CONFLICTS_RESULT = new RebaseResult(Status.STASH_APPLY_CONFLICTS);

	private final Status status;

	private RebaseResult(Status status) {
		this.status = status;
	}

	static RebaseResult result(RebaseResult.Status status) {
		return new RebaseResult(status);
	}

	static RebaseResult failed() {
		return new RebaseResult(Status.FAILED);
	}

	static RebaseResult conflicts() {
		return new RebaseResult(Status.CONFLICTS);
	}

	static RebaseResult uncommittedChanges() {
		return new RebaseResult(Status.UNCOMMITTED_CHANGES);
	}

	public Status getStatus() {
		return status;
	}
}
