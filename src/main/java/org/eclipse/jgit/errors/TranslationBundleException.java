/*
 * Copyright (C) 2010, Sasa Zivkov <sasa.zivkov@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.errors;

public abstract class TranslationBundleException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	protected TranslationBundleException(String message, Exception cause) {
		super(message, cause);
	}
}
