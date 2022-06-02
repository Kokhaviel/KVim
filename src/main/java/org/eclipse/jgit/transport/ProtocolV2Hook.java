/*
 * Copyright (C) 2018, Google LLC. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.transport;

public interface ProtocolV2Hook {

	ProtocolV2Hook DEFAULT = new ProtocolV2Hook() {
	};

	default void onCapabilities() {
	}

	default void onLsRefs() {
	}

	default void onFetch() {
	}

	default void onObjectInfo() {
	}
}
