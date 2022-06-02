/*
 * Copyright (C) 2010, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import java.util.Map;

import org.eclipse.jgit.lib.Ref;

public interface RefFilter {
	RefFilter DEFAULT = (Map<String, Ref> refs) -> refs;

	Map<String, Ref> filter(Map<String, Ref> refs);
}
