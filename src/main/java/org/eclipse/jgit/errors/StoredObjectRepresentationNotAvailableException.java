/*
 * Copyright (C) 2010, 2021 Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.errors;

public class StoredObjectRepresentationNotAvailableException extends Exception {

	private static final long serialVersionUID = 1L;

	public StoredObjectRepresentationNotAvailableException(Throwable cause) {
		super(cause);
	}
}
