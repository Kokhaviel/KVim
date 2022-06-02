/*
 * Copyright (C) 2011, 2013 Christian Halstrick <christian.halstrick@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.api;

import org.eclipse.jgit.lib.IndexDiff;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class Status {
	private final IndexDiff diff;
	private final boolean clean;
	private final boolean hasUncommittedChanges;

	public Status(IndexDiff diff) {
		super();
		this.diff = diff;
		hasUncommittedChanges = !diff.getAdded().isEmpty()
				|| !diff.getChanged().isEmpty() || !diff.getRemoved().isEmpty() || !diff.getMissing().isEmpty()
				|| !diff.getModified().isEmpty() || !diff.getConflicting().isEmpty();
		clean = !hasUncommittedChanges && diff.getUntracked().isEmpty();
	}

	public boolean isClean() {
		return clean;
	}

	public boolean hasUncommittedChanges() {
		return hasUncommittedChanges;
	}

	public Set<String> getAdded() {
		return Collections.unmodifiableSet(diff.getAdded());
	}

	public Set<String> getChanged() {
		return Collections.unmodifiableSet(diff.getChanged());
	}

	public Set<String> getRemoved() {
		return Collections.unmodifiableSet(diff.getRemoved());
	}

	public Set<String> getMissing() {
		return Collections.unmodifiableSet(diff.getMissing());
	}

	public Set<String> getModified() {
		return Collections.unmodifiableSet(diff.getModified());
	}

	public Set<String> getUntracked() {
		return Collections.unmodifiableSet(diff.getUntracked());
	}

	public Set<String> getConflicting() {
		return Collections.unmodifiableSet(diff.getConflicting());
	}

	public Set<String> getIgnoredNotInIndex() {
		return Collections.unmodifiableSet(diff.getIgnoredNotInIndex());
	}

	public Set<String> getUncommittedChanges() {
		Set<String> uncommittedChanges = new HashSet<>();
		uncommittedChanges.addAll(diff.getAdded());
		uncommittedChanges.addAll(diff.getChanged());
		uncommittedChanges.addAll(diff.getRemoved());
		uncommittedChanges.addAll(diff.getMissing());
		uncommittedChanges.addAll(diff.getModified());
		uncommittedChanges.addAll(diff.getConflicting());
		return uncommittedChanges;
	}
}
