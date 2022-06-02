/*
 * Copyright (C) 2013, Robin Stocker <robin@nibor.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.treewalk.filter;

import org.eclipse.jgit.errors.StopWalkException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.text.MessageFormat;

public class TreeFilterMarker {

	private final TreeFilter[] filters;

	public TreeFilterMarker(TreeFilter[] markTreeFilters) {
		if (markTreeFilters.length > Integer.SIZE) {
			throw new IllegalArgumentException(MessageFormat.format(
					JGitText.get().treeFilterMarkerTooManyFilters, Integer.SIZE,
					markTreeFilters.length));
		}
		filters = new TreeFilter[markTreeFilters.length];
		System.arraycopy(markTreeFilters, 0, filters, 0, markTreeFilters.length);
	}

	public int getMarks(TreeWalk walk) throws IOException {
		int marks = 0;
		for (int index = 0; index < filters.length; index++) {
			TreeFilter filter = filters[index];
			if (filter != null) {
				try {
					boolean marked = filter.include(walk);
					if (marked)
						marks |= (1 << index);
				} catch (StopWalkException e) {
					filters[index] = null;
				}
			}
		}
		return marks;
	}

}
