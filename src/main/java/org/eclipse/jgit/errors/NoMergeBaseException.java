/*
 * Copyright (C) 2013, Christian Halstrick <christian.halstrick@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.errors;

import java.io.IOException;
import java.text.MessageFormat;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.merge.RecursiveMerger;

public class NoMergeBaseException extends IOException {
	private static final long serialVersionUID = 1L;

	public enum MergeBaseFailureReason {
		MULTIPLE_MERGE_BASES_NOT_SUPPORTED,
		TOO_MANY_MERGE_BASES,
		CONFLICTS_DURING_MERGE_BASE_CALCULATION
	}

	public NoMergeBaseException(MergeBaseFailureReason reason, String message) {
		super(MessageFormat.format(JGitText.get().noMergeBase,
				reason.toString(), message));
	}
}
