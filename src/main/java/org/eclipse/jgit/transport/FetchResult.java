/*
 * Copyright (C) 2009, Google Inc.
 * Copyright (C) 2008, Marek Zawirski <marek.zawirski@gmail.com>
 * Copyright (C) 2007, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FetchResult extends OperationResult {
	private final List<FetchHeadRecord> forMerge;

	private final Map<String, FetchResult> submodules;

	FetchResult() {
		forMerge = new ArrayList<>();
		submodules = new HashMap<>();
	}

	void add(FetchHeadRecord r) {
		if(!r.notForMerge)
			forMerge.add(r);
	}

	public void addSubmodule(String path, FetchResult result) {
		submodules.put(path, result);
	}
}
