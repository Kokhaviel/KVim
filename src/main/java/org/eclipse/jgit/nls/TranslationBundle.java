/*
 * Copyright (C) 2010, Sasa Zivkov <sasa.zivkov@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.nls;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

import org.eclipse.jgit.errors.TranslationBundleLoadingException;
import org.eclipse.jgit.errors.TranslationStringMissingException;

public abstract class TranslationBundle {

	void load(Locale locale)
			throws TranslationBundleLoadingException {
		Class<?> bundleClass = getClass();
		ResourceBundle resourceBundle;
		try {
			resourceBundle = ResourceBundle.getBundle(bundleClass.getName(),
					locale, bundleClass.getClassLoader());
		} catch(MissingResourceException e) {
			throw new TranslationBundleLoadingException(bundleClass, locale, e);
		}

		for(Field field : bundleClass.getFields()) {
			if(field.getType().equals(String.class)) {
				try {
					String translatedText = resourceBundle.getString(field.getName());
					field.set(this, translatedText);
				} catch(MissingResourceException e) {
					throw new TranslationStringMissingException(bundleClass, locale, field.getName(), e);
				} catch(IllegalArgumentException | IllegalAccessException e) {
					throw new Error(e);
				}
			}
		}
	}
}
