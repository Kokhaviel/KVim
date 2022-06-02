/*
 * Copyright (C) 2011, Roberto Tyley <roberto.tyley@gmail.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.api;

import org.eclipse.jgit.transport.Transport;

public interface TransportConfigCallback {

	void configure(Transport transport);
}
