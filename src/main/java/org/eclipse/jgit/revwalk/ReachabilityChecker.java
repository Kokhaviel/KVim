/*
 * Copyright (C) 2019, Google LLC. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.revwalk;

import java.io.IOException;
import java.util.Collection;
import java.util.Optional;
import java.util.stream.Stream;

import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;

public interface ReachabilityChecker {

	@Deprecated
	default Optional<RevCommit> areAllReachable(Collection<RevCommit> targets, Collection<RevCommit> starters) throws IOException {
		return areAllReachable(targets, starters.stream());
	}

	Optional<RevCommit> areAllReachable(Collection<RevCommit> targets, Stream<RevCommit> starters) throws IOException;
}
