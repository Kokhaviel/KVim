/*
 * Copyright (C) 2009, Google Inc.
 * Copyright (C) 2008, Marek Zawirski <marek.zawirski@gmail.com>
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.revwalk;

import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;

import java.io.IOException;

public class RevTree extends RevObject {

	protected RevTree(AnyObjectId id) {
		super(id);
	}

	@Override
	public final int getType() {
		return Constants.OBJ_TREE;
	}

	@Override
	void parseHeaders(RevWalk walk) throws IOException {
		if(walk.reader.has(this))
			flags |= PARSED;
		else
			throw new MissingObjectException(this, getType());
	}

	@Override
	void parseBody(RevWalk walk) throws IOException {
		if((flags & PARSED) == 0)
			parseHeaders(walk);
	}
}
