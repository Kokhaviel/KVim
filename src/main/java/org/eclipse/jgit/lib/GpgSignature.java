/*
 * Copyright (C) 2018, Salesforce. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.lib;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.io.Serializable;

import org.eclipse.jgit.annotations.NonNull;

public class GpgSignature implements Serializable {

	private static final long serialVersionUID = 1L;
	private final byte[] signature;

	public GpgSignature(@NonNull byte[] signature) {
		this.signature = signature;
	}

	public String toExternalString() {
		return new String(signature, US_ASCII);
	}

	@Override
	public String toString() {

		return "GpgSignature[" + (this.signature != null ? "length " + signature.length : "null") + "]";
	}
}
