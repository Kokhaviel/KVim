/*
 * Copyright (C) 2009, Google Inc.
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.revwalk;

import java.io.IOException;

final class DelayRevQueue extends Generator {
	private static final int OVER_SCAN = PendingGenerator.OVER_SCAN;
	private final Generator pending;
	private final FIFORevQueue delay;
	private int size;

	DelayRevQueue(Generator g) {
		super(g.firstParent);
		pending = g;
		delay = new FIFORevQueue();
	}

	@Override
	int outputType() {
		return pending.outputType();
	}

	@Override
	RevCommit next() throws IOException {
		while(size < OVER_SCAN) {
			final RevCommit c = pending.next();
			if(c == null)
				break;
			delay.add(c);
			size++;
		}

		final RevCommit c = delay.next();
		if(c == null)
			return null;
		size--;
		return c;
	}
}
