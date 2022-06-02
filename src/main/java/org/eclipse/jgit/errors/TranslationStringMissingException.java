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

import java.util.Locale;

public class TranslationStringMissingException extends TranslationBundleException {
	private static final long serialVersionUID = 1L;

	public TranslationStringMissingException(Class bundleClass, Locale locale, String key, Exception cause) {
		super("Translation missing for [" + bundleClass.getName() + ", " + locale.toString() + ", " + key + "]", cause);
	}
}
