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

import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

class GlobalBundleCache {
	private static final Map<Locale, Map<Class<?>, TranslationBundle>> cachedBundles
			= new HashMap<>();

	@SuppressWarnings("unchecked")
	static synchronized <T extends TranslationBundle> T lookupBundle(Locale locale, Class<T> type) {
		try {
			Map<Class<?>, TranslationBundle> bundles = cachedBundles.computeIfAbsent(locale, k -> new HashMap<>());
			TranslationBundle bundle = bundles.get(type);
			if(bundle == null) {
				bundle = type.getDeclaredConstructor().newInstance();
				bundle.load(locale);
				bundles.put(type, bundle);
			}
			return (T) bundle;
		} catch(InstantiationException | IllegalAccessException
				| InvocationTargetException | NoSuchMethodException e) {
			throw new Error(e);
		}
	}

	static void clear() {
		cachedBundles.clear();
	}
}
