/*
 * Copyright (C) 2011, Tomasz Zarna <Tomasz.Zarna@pl.ibm.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.revwalk.filter;

import org.eclipse.jgit.errors.StopWalkException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

import java.io.IOException;

public class SkipRevFilter extends RevFilter {

	private final int skip;
	private int count;

	public static RevFilter create(int skip) {
		if(skip < 0)
			throw new IllegalArgumentException(
					JGitText.get().skipMustBeNonNegative);
		return new SkipRevFilter(skip);
	}

	private SkipRevFilter(int skip) {
		this.skip = skip;
	}

	@Override
	public boolean include(RevWalk walker, RevCommit cmit)
			throws StopWalkException, IOException {
		return skip <= count++;
	}

	@Override
	public RevFilter clone() {
		return new SkipRevFilter(skip);
	}
}
