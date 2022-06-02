/*
 * Copyright (C) 2014, 2020 Andrey Loskutov <loskutov@gmx.de> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.ignore;

public interface IMatcher {

	IMatcher NO_MATCH = new IMatcher() {

		@Override
		public boolean matches(String path, boolean assumeDirectory,
				boolean pathMatch) {
			return false;
		}

		@Override
		public boolean matches(String segment, int startIncl, int endExcl) {
			return false;
		}
	};

	boolean matches(String path, boolean assumeDirectory, boolean pathMatch);

	boolean matches(String segment, int startIncl, int endExcl);
}
