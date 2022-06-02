/*
 * Copyright (C) 2018, Google LLC. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.transport;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Set;

import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.lib.ObjectId;

abstract class FetchRequest {

	final Set<ObjectId> wantIds;
	final int depth;
	final Set<ObjectId> clientShallowCommits;
	final FilterSpec filterSpec;
	final Set<String> clientCapabilities;
	final int deepenSince;
	final List<String> deepenNotRefs;

	@Nullable
	final String agent;

	FetchRequest(@NonNull Set<ObjectId> wantIds, int depth,
				 @NonNull Set<ObjectId> clientShallowCommits,
				 @NonNull FilterSpec filterSpec,
				 @NonNull Set<String> clientCapabilities, int deepenSince,
				 @NonNull List<String> deepenNotRefs, @Nullable String agent) {
		this.wantIds = requireNonNull(wantIds);
		this.depth = depth;
		this.clientShallowCommits = requireNonNull(clientShallowCommits);
		this.filterSpec = requireNonNull(filterSpec);
		this.clientCapabilities = requireNonNull(clientCapabilities);
		this.deepenSince = deepenSince;
		this.deepenNotRefs = requireNonNull(deepenNotRefs);
		this.agent = agent;
	}

	@NonNull
	Set<ObjectId> getWantIds() {
		return wantIds;
	}

	int getDepth() {
		return depth;
	}

	@NonNull
	Set<ObjectId> getClientShallowCommits() {
		return clientShallowCommits;
	}

	@NonNull
	FilterSpec getFilterSpec() {
		return filterSpec;
	}

	@NonNull
	Set<String> getClientCapabilities() {
		return clientCapabilities;
	}

	int getDeepenSince() {
		return deepenSince;
	}

	@NonNull
	List<String> getDeepenNotRefs() {
		return deepenNotRefs;
	}
}
