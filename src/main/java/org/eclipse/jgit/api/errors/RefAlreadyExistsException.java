/*
 * Copyright (C) 2010, 2020 Mathias Kinzler <mathias.kinzler@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.api.errors;

import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.lib.RefUpdate;

public class RefAlreadyExistsException extends GitAPIException {
	private static final long serialVersionUID = 1L;

	public RefAlreadyExistsException(String message) {
		this(message, null);
	}

	public RefAlreadyExistsException(String message,
			@Nullable RefUpdate.Result updateResult) {
		super(message);
	}

}
