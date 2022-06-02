/*
 * Copyright (C) 2010, Mathias Kinzler <mathias.kinzler@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.api;

import java.util.List;

public class CheckoutResult {

	public static final CheckoutResult ERROR_RESULT = new CheckoutResult(Status.ERROR, null);
	public static final CheckoutResult NOT_TRIED_RESULT = new CheckoutResult(Status.NOT_TRIED, null);
	
	public enum Status {
		NOT_TRIED,
		OK,
		CONFLICTS,
		NONDELETED,
		ERROR
	}

	CheckoutResult(Status status, List<String> fileList) {
		this(status, fileList, null);
	}

	CheckoutResult(Status status, List<String> fileList,
				   List<String> removed) {

	}

	CheckoutResult(List<String> removed) {

	}
}
