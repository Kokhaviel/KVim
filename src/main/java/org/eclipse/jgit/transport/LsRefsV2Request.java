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

import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LsRefsV2Request {
	private final List<String> refPrefixes;

	private final boolean symrefs;

	private final boolean peel;

	@Nullable
	private final String agent;

	private LsRefsV2Request(List<String> refPrefixes, boolean symrefs,
							boolean peel, @Nullable String agent) {
		this.refPrefixes = refPrefixes;
		this.symrefs = symrefs;
		this.peel = peel;
		this.agent = agent;
	}

	public List<String> getRefPrefixes() {
		return refPrefixes;
	}

	public boolean getSymrefs() {
		return symrefs;
	}

	public boolean getPeel() {
		return peel;
	}

	@Nullable
	public String getAgent() {
		return agent;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {
		private List<String> refPrefixes = Collections.emptyList();

		private boolean symrefs;

		private boolean peel;

		private final List<String> serverOptions = new ArrayList<>();

		private String agent;

		private Builder() {
		}

		public Builder setRefPrefixes(List<String> value) {
			refPrefixes = value;
			return this;
		}

		public Builder setSymrefs(boolean value) {
			symrefs = value;
			return this;
		}

		public Builder setPeel(boolean value) {
			peel = value;
			return this;
		}

		public Builder addServerOption(@NonNull String value) {
			serverOptions.add(value);
			return this;
		}

		public Builder setAgent(@Nullable String value) {
			agent = value;
			return this;
		}

		public LsRefsV2Request build() {
			return new LsRefsV2Request(
					Collections.unmodifiableList(refPrefixes), symrefs, peel,
					agent);
		}
	}
}
