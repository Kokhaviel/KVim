/*
 * Copyright (C) 2010, 2013 Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.lib;

import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.annotations.Nullable;

import java.io.IOException;
import java.util.*;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

public abstract class RefDatabase {

	protected static final String[] SEARCH_PATH = {"",
			Constants.R_REFS,
			Constants.R_TAGS,
			Constants.R_HEADS,
			Constants.R_REMOTES
	};

	public static final int MAX_SYMBOLIC_REF_DEPTH = 5;

	public static final String ALL = "";

	public abstract void create() throws IOException;

	public abstract void close();

	public boolean hasVersioning() {
		return false;
	}

	public abstract boolean isNameConflicting(String name) throws IOException;

	@NonNull
	public abstract RefUpdate newUpdate(String name, boolean detach)
			throws IOException;

	@NonNull
	public BatchRefUpdate newBatchUpdate() {
		return new BatchRefUpdate(this);
	}

	public boolean performsAtomicTransactions() {
		return false;
	}

	@Deprecated
	@Nullable
	public final Ref getRef(String name) throws IOException {
		return findRef(name);
	}

	@Nullable
	public final Ref findRef(String name) throws IOException {
		String[] names = new String[SEARCH_PATH.length];
		for(int i = 0; i < SEARCH_PATH.length; i++) {
			names[i] = SEARCH_PATH[i] + name;
		}
		return firstExactRef(names);
	}

	@Nullable
	public abstract Ref exactRef(String name) throws IOException;

	@NonNull
	public Map<String, Ref> exactRef(String... refs) throws IOException {
		Map<String, Ref> result = new HashMap<>(refs.length);
		for(String name : refs) {
			Ref ref = exactRef(name);
			if(ref != null) {
				result.put(name, ref);
			}
		}
		return result;
	}

	@Nullable
	public Ref firstExactRef(String... refs) throws IOException {
		for(String name : refs) {
			Ref ref = exactRef(name);
			if(ref != null) {
				return ref;
			}
		}
		return null;
	}

	@NonNull
	public List<Ref> getRefs() throws IOException {
		return getRefsByPrefix(ALL);
	}

	@NonNull
	@Deprecated
	public abstract Map<String, Ref> getRefs(String prefix) throws IOException;

	@NonNull
	public List<Ref> getRefsByPrefix(String prefix) throws IOException {
		Map<String, Ref> coarseRefs;
		int lastSlash = prefix.lastIndexOf('/');
		if(lastSlash == -1) {
			coarseRefs = getRefs(ALL);
		} else {
			coarseRefs = getRefs(prefix.substring(0, lastSlash + 1));
		}

		List<Ref> result;
		if(lastSlash + 1 == prefix.length()) {
			result = new ArrayList<>(coarseRefs.values());
		} else {
			String p = prefix.substring(lastSlash + 1);
			result = coarseRefs.entrySet().stream()
					.filter(e -> e.getKey().startsWith(p))
					.map(Map.Entry::getValue)
					.collect(toList());
		}
		return Collections.unmodifiableList(result);
	}

	@NonNull
	public List<Ref> getRefsByPrefix(String... prefixes) throws IOException {
		List<Ref> result = new ArrayList<>();
		for(String prefix : prefixes) {
			result.addAll(getRefsByPrefix(prefix));
		}
		return Collections.unmodifiableList(result);
	}

	@NonNull
	public Set<Ref> getTipsWithSha1(ObjectId id) throws IOException {
		return getRefs().stream().filter(r -> id.equals(r.getObjectId())
				|| id.equals(r.getPeeledObjectId())).collect(toSet());
	}

	@NonNull
	public abstract List<Ref> getAdditionalRefs() throws IOException;

	@NonNull
	public abstract Ref peel(Ref ref) throws IOException;

	public void refresh() {
	}

	@Nullable
	public static Ref findRef(Map<String, Ref> map, String name) {
		for(String prefix : SEARCH_PATH) {
			String fullname = prefix + name;
			Ref ref = map.get(fullname);
			if(ref != null)
				return ref;
		}
		return null;
	}
}
