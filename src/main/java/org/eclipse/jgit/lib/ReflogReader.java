/*
 * Copyright (C) 2013, Robin Rosenberg <robin.rosenberg@dewire.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.lib;

import java.io.IOException;
import java.util.List;

public interface ReflogReader {

	List<ReflogEntry> getReverseEntries() throws IOException;

	ReflogEntry getReverseEntry(int number) throws IOException;

	List<ReflogEntry> getReverseEntries(int max) throws IOException;
}
