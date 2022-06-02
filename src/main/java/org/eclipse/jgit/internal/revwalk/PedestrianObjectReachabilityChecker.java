/*
 * Copyright (C) 2020, Google LLC and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.internal.revwalk;

import java.io.IOException;
import java.io.InvalidObjectException;
import java.util.Collection;
import java.util.Iterator;
import java.util.Optional;
import java.util.stream.Stream;

import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.revwalk.ObjectReachabilityChecker;
import org.eclipse.jgit.revwalk.ObjectWalk;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevSort;

public class PedestrianObjectReachabilityChecker implements ObjectReachabilityChecker {
	private final ObjectWalk walk;

	public PedestrianObjectReachabilityChecker(ObjectWalk walk) {
		this.walk = walk;
	}

	@Override
	public Optional<RevObject> areAllReachable(Collection<RevObject> targets,
											   Stream<RevObject> starters) throws IOException {
		try {
			walk.reset();
			walk.sort(RevSort.TOPO);
			for(RevObject target : targets) {
				walk.markStart(target);
			}

			Iterator<RevObject> iterator = starters.iterator();
			while(iterator.hasNext()) {
				RevObject o = iterator.next();
				walk.markUninteresting(o);

				RevObject peeled = walk.peel(o);
				if(peeled instanceof RevCommit) {
					walk.markUninteresting(((RevCommit) peeled).getTree());
				}
			}

			RevCommit commit = walk.next();
			if(commit != null) {
				return Optional.of(commit);
			}

			RevObject object = walk.nextObject();
			if(object != null) {
				return Optional.of(object);
			}

			return Optional.empty();
		} catch(MissingObjectException | InvalidObjectException e) {
			throw new IllegalStateException(e);
		}
	}
}
