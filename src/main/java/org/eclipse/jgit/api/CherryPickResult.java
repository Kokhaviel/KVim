/*
 * Copyright (C) 2011, Philipp Thun <philipp.thun@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.api;

import org.eclipse.jgit.revwalk.RevCommit;

public class CherryPickResult {

	public enum CherryPickStatus {
		OK {
			@Override
			public String toString() {
				return "Ok";
			}
		},
		FAILED {
			@Override
			public String toString() {
				return "Failed";
			}
		},
		CONFLICTING {
			@Override
			public String toString() {
				return "Conflicting";
			}
		}
	}

	private final CherryPickStatus status;
	private final RevCommit newHead;

	public CherryPickResult(RevCommit newHead) {
		this.status = CherryPickStatus.OK;
		this.newHead = newHead;
	}

	public CherryPickResult() {
		this.status = CherryPickStatus.FAILED;
		this.newHead = null;
	}

	private CherryPickResult(CherryPickStatus status) {
		this.status = status;
		this.newHead = null;
	}

	public static final CherryPickResult CONFLICT = new CherryPickResult(CherryPickStatus.CONFLICTING);

	public CherryPickStatus getStatus() {
		return status;
	}

	public RevCommit getNewHead() {
		return newHead;
	}

}
