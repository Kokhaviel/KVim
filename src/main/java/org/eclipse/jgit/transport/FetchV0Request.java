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

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.lib.ObjectId;

final class FetchV0Request extends FetchRequest {

	FetchV0Request(@NonNull Set<ObjectId> wantIds, int depth, @NonNull Set<ObjectId> clientShallowCommits,
				   @NonNull FilterSpec filterSpec, @NonNull Set<String> clientCapabilities, @Nullable String agent) {
		super(wantIds, depth, clientShallowCommits, filterSpec, clientCapabilities, 0, Collections.emptyList(), agent);
	}

	static final class Builder {

		int depth;
		final Set<ObjectId> wantIds = new HashSet<>();
		final Set<ObjectId> clientShallowCommits = new HashSet<>();
		FilterSpec filterSpec = FilterSpec.NO_FILTER;
		final Set<String> clientCaps = new HashSet<>();
		String agent;

		Builder addWantId(ObjectId objectId) {
			wantIds.add(objectId);
			return this;
		}

		Builder setDepth(int d) {
			depth = d;
			return this;
		}

		Builder addClientShallowCommit(ObjectId shallowOid) {
			clientShallowCommits.add(shallowOid);
			return this;
		}

		Builder addClientCapabilities(Collection<String> clientCapabilities) {
			clientCaps.addAll(clientCapabilities);
			return this;
		}

		Builder setAgent(String clientAgent) {
			agent = clientAgent;
			return this;
		}

		Builder setFilterSpec(@NonNull FilterSpec filter) {
			filterSpec = requireNonNull(filter);
			return this;
		}

		FetchV0Request build() {
			return new FetchV0Request(wantIds, depth, clientShallowCommits,
					filterSpec, clientCaps, agent);
		}

	}
}
