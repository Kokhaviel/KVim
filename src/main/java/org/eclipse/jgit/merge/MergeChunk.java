/*
 * Copyright (C) 2009, Christian Halstrick <christian.halstrick@sap.com>
 * Copyright (C) 2010, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.merge;

public class MergeChunk {
	public enum ConflictState {
		NO_CONFLICT,
		FIRST_CONFLICTING_RANGE,
		NEXT_CONFLICTING_RANGE
	}

	private final int sequenceIndex;
	private final int begin;
	private final int end;
	private final ConflictState conflictState;

	protected MergeChunk(int sequenceIndex, int begin, int end,
						 ConflictState conflictState) {
		this.sequenceIndex = sequenceIndex;
		this.begin = begin;
		this.end = end;
		this.conflictState = conflictState;
	}

	public int getSequenceIndex() {
		return sequenceIndex;
	}

	public int getBegin() {
		return begin;
	}

	public int getEnd() {
		return end;
	}

	public ConflictState getConflictState() {
		return conflictState;
	}
}
