/*
 * Copyright (C) 2018, Google LLC. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.lib;

public final class GitmoduleEntry {

	private final AnyObjectId treeId;
	private final AnyObjectId blobId;

	public GitmoduleEntry(AnyObjectId treeId, AnyObjectId blobId) {
		this.treeId = treeId.copy();
		this.blobId = blobId.copy();
	}

	public AnyObjectId getBlobId() {
		return blobId;
	}

	public AnyObjectId getTreeId() {
		return treeId;
	}
}