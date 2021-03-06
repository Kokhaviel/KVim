/*
 * Copyright (C) 2010, Christian Halstrick <christian.halstrick@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.api.errors;

public class MultipleParentsNotAllowedException extends GitAPIException {
	private static final long serialVersionUID = 1L;

	public MultipleParentsNotAllowedException(String message) {
		super(message);
	}
}
