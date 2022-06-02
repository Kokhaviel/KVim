/*
 * Copyright (C) 2008-2009, Google Inc.
 * Copyright (C) 2009, Matthias Sohn <matthias.sohn@sap.com>
 * Copyright (C) 2012, Research In Motion Limited and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.merge;

import java.text.MessageFormat;
import java.util.HashMap;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Repository;

public abstract class MergeStrategy {
	public static final MergeStrategy OURS = new StrategyOneSided("ours", 0);
	public static final MergeStrategy THEIRS = new StrategyOneSided("theirs", 1);
	public static final ThreeWayMergeStrategy SIMPLE_TWO_WAY_IN_CORE = new StrategySimpleTwoWayInCore();
	public static final ThreeWayMergeStrategy RESOLVE = new StrategyResolve();
	public static final ThreeWayMergeStrategy RECURSIVE = new StrategyRecursive();
	private static final HashMap<String, MergeStrategy> STRATEGIES = new HashMap<>();

	static {
		register(OURS);
		register(THEIRS);
		register(SIMPLE_TWO_WAY_IN_CORE);
		register(RESOLVE);
		register(RECURSIVE);
	}

	public static void register(MergeStrategy imp) {
		register(imp.getName(), imp);
	}

	public static synchronized void register(final String name,
											 final MergeStrategy imp) {
		if(STRATEGIES.containsKey(name)) throw new IllegalArgumentException(MessageFormat.format(
				JGitText.get().mergeStrategyAlreadyExistsAsDefault, name));
		STRATEGIES.put(name, imp);
	}

	public static synchronized MergeStrategy get(String name) {
		return STRATEGIES.get(name);
	}

	public static synchronized MergeStrategy[] get() {
		final MergeStrategy[] r = new MergeStrategy[STRATEGIES.size()];
		STRATEGIES.values().toArray(r);
		return r;
	}

	public abstract String getName();

	public abstract Merger newMerger(Repository db);

	public abstract Merger newMerger(Repository db, boolean inCore);
}
