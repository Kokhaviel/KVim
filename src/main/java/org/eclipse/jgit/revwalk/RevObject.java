/*
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.revwalk;

import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectIdOwnerMap;

import java.io.IOException;

public abstract class RevObject extends ObjectIdOwnerMap.Entry {
	static final int PARSED = 1;

	int flags;

	RevObject(AnyObjectId name) {
		super(name);
	}

	abstract void parseHeaders(RevWalk walk) throws IOException;

	abstract void parseBody(RevWalk walk) throws IOException;

	public abstract int getType();

	public final ObjectId getId() {
		return this;
	}

	public final boolean has(RevFlag flag) {
		return (flags & flag.mask) != 0;
	}

	public final boolean hasAll(RevFlagSet set) {
		return (flags & set.mask) == set.mask;
	}

	public final void add(RevFlag flag) {
		flags |= flag.mask;
	}

	public final void add(RevFlagSet set) {
		flags |= set.mask;
	}

	public final void remove(RevFlag flag) {
		flags &= ~flag.mask;
	}

	public final void remove(RevFlagSet set) {
		flags &= ~set.mask;
	}

	@Override
	public String toString() {
		final StringBuilder s = new StringBuilder();
		s.append(Constants.typeString(getType()));
		s.append(' ');
		s.append(name());
		s.append(' ');
		appendCoreFlags(s);
		return s.toString();
	}

	protected void appendCoreFlags(StringBuilder s) {
		s.append((flags & RevWalk.TOPO_DELAY) != 0 ? 'o' : '-');
		s.append((flags & RevWalk.TOPO_QUEUED) != 0 ? 'q' : '-');
		s.append((flags & RevWalk.TEMP_MARK) != 0 ? 't' : '-');
		s.append((flags & RevWalk.REWRITE) != 0 ? 'r' : '-');
		s.append((flags & RevWalk.UNINTERESTING) != 0 ? 'u' : '-');
		s.append((flags & RevWalk.SEEN) != 0 ? 's' : '-');
		s.append((flags & RevWalk.PARSED) != 0 ? 'p' : '-');
	}
}
