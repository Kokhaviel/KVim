/*
 * Copyright (C) 2008, Charles O'Farrell <charleso@charleso.org>
 * Copyright (C) 2010, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.lib;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

public class RefComparator implements Comparator<Ref> {

	public static final RefComparator INSTANCE = new RefComparator();

	@Override
	public int compare(Ref o1, Ref o2) {
		return compareTo(o1, o2);
	}

	public static Collection<Ref> sort(Collection<Ref> refs) {
		final List<Ref> r = new ArrayList<>(refs);
		r.sort(INSTANCE);
		return r;
	}

	public static int compareTo(Ref o1, String o2) {
		return o1.getName().compareTo(o2);
	}

	public static int compareTo(Ref o1, Ref o2) {
		return o1.getName().compareTo(o2.getName());
	}
}
