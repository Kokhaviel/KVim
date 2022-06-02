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

abstract class AbstractRevQueue extends Generator {
	static final AbstractRevQueue EMPTY_QUEUE = new AlwaysEmptyQueue();

	int outputType;

	AbstractRevQueue(boolean firstParent) {
		super(firstParent);
	}

	public abstract void add(RevCommit c);

	public final void add(RevCommit c, RevFlag queueControl) {
		if(!c.has(queueControl)) {
			c.add(queueControl);
			add(c);
		}
	}

	@Override
	public abstract RevCommit next();

	public abstract void clear();

	abstract boolean everbodyHasFlag(int f);

	abstract boolean anybodyHasFlag();

	@Override
	int outputType() {
		return outputType;
	}

	protected static void describe(StringBuilder s, RevCommit c) {
		s.append(c.toString());
		s.append('\n');
	}

	private static class AlwaysEmptyQueue extends AbstractRevQueue {
		private AlwaysEmptyQueue() {
			super(false);
		}

		@Override
		public void add(RevCommit c) {
			throw new UnsupportedOperationException();
		}

		@Override
		public RevCommit next() {
			return null;
		}

		@Override
		boolean anybodyHasFlag() {
			return false;
		}

		@Override
		boolean everbodyHasFlag(int f) {
			return true;
		}

		@Override
		public void clear() {
		}

	}
}
