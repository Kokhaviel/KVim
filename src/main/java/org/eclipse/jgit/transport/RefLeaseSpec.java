/*
 * Copyright (C) 2017 Two Sigma Open Source and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import java.io.Serializable;

public class RefLeaseSpec implements Serializable {
	private static final long serialVersionUID = 1L;

	private final String ref;
	private final String expected;

	public RefLeaseSpec(String ref, String expected) {
		this.ref = ref;
		this.expected = expected;
	}

	public String getRef() {
		return ref;
	}

	public String getExpected() {
		return expected;
	}

	@Override
	public String toString() {
		return getRef() + ':' + getExpected();
	}
}
