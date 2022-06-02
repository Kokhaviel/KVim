/*
 * Copyright (C) 2009, Christian Halstrick <christian.halstrick@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.merge;

import java.util.Iterator;
import java.util.List;

import org.eclipse.jgit.diff.Sequence;
import org.eclipse.jgit.merge.MergeChunk.ConflictState;
import org.eclipse.jgit.util.IntList;

public class MergeResult<S extends Sequence> implements Iterable<MergeChunk> {
	private final List<S> sequences;

	final IntList chunks = new IntList();

	private boolean containsConflicts = false;

	public MergeResult(List<S> sequences) {
		this.sequences = sequences;
	}

	public void add(int srcIdx, int begin, int end, ConflictState conflictState) {
		chunks.add(conflictState.ordinal());
		chunks.add(srcIdx);
		chunks.add(begin);
		chunks.add(end);
		if(conflictState != ConflictState.NO_CONFLICT)
			containsConflicts = true;
	}

	public List<S> getSequences() {
		return sequences;
	}

	static final ConflictState[] states = ConflictState.values();

	@Override
	public Iterator<MergeChunk> iterator() {
		return new Iterator<MergeChunk>() {
			int idx;

			@Override
			public boolean hasNext() {
				return (idx < chunks.size());
			}

			@Override
			public MergeChunk next() {
				ConflictState state = states[chunks.get(idx++)];
				int srcIdx = chunks.get(idx++);
				int begin = chunks.get(idx++);
				int end = chunks.get(idx++);
				return new MergeChunk(srcIdx, begin, end, state);
			}

			@Override
			public void remove() {
				throw new UnsupportedOperationException();
			}
		};
	}

	public boolean containsConflicts() {
		return containsConflicts;
	}

	protected void setContainsConflicts(boolean containsConflicts) {
		this.containsConflicts = containsConflicts;
	}
}
