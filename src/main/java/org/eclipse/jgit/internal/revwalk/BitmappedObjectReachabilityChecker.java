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
import java.util.*;
import java.util.stream.Stream;

import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.BitmapIndex.BitmapBuilder;
import org.eclipse.jgit.revwalk.BitmapWalker;
import org.eclipse.jgit.revwalk.ObjectReachabilityChecker;
import org.eclipse.jgit.revwalk.ObjectWalk;
import org.eclipse.jgit.revwalk.RevObject;

public class BitmappedObjectReachabilityChecker implements ObjectReachabilityChecker {

	private final ObjectWalk walk;

	public BitmappedObjectReachabilityChecker(ObjectWalk walk) {
		this.walk = walk;
	}

	@Override
	public Optional<RevObject> areAllReachable(Collection<RevObject> targets,
											   Stream<RevObject> starters) throws IOException {

		try {
			List<RevObject> remainingTargets = new ArrayList<>(targets);
			BitmapWalker bitmapWalker = new BitmapWalker(walk,
					walk.getObjectReader().getBitmapIndex(), null);

			Iterator<RevObject> starterIt = starters.iterator();
			BitmapBuilder seen = null;
			while(starterIt.hasNext()) {
				List<RevObject> asList = Collections.singletonList(starterIt.next());
				BitmapBuilder visited = bitmapWalker.findObjects(asList, seen,
						true);
				seen = seen == null ? visited : seen.or(visited);

				remainingTargets.removeIf(seen::contains);
				if(remainingTargets.isEmpty()) {
					return Optional.empty();
				}
			}

			return Optional.of(remainingTargets.get(0));
		} catch(MissingObjectException | IncorrectObjectTypeException e) {
			throw new IllegalStateException(e);
		}
	}
}
