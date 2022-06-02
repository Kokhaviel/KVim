/*
 * Copyright (C) 2010, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.notes;

import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.util.Paths;

class NonNoteEntry extends ObjectId {

	private final byte[] name;
	private final FileMode mode;

	NonNoteEntry next;

	NonNoteEntry(byte[] name, FileMode mode, AnyObjectId id) {
		super(id);
		this.name = name;
		this.mode = mode;
	}

	void format(TreeFormatter fmt) {
		fmt.append(name, mode, this);
	}

	int treeEntrySize() {
		return TreeFormatter.entrySize(mode, name.length);
	}

	int pathCompare(byte[] bBuf, int bPos, int bLen, FileMode bMode) {
		return Paths.compare(
				name, 0, name.length, mode.getBits(),
				bBuf, bPos, bLen, bMode.getBits());
	}
}
