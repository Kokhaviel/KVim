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

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class NLS {

	private static final InheritableThreadLocal<NLS> local = new InheritableThreadLocal<>();

	public static void setLocale(Locale locale) {
		local.set(new NLS(locale));
	}

	private static NLS useJVMDefaultInternal() {
		NLS b = new NLS(Locale.getDefault());
		local.set(b);
		return b;
	}

	public static <T extends TranslationBundle> T getBundleFor(Class<T> type) {
		NLS b = local.get();
		if(b == null) {
			b = useJVMDefaultInternal();
		}
		return b.get(type);
	}

	public static void clear() {
		local.remove();
		GlobalBundleCache.clear();
	}

	private final Locale locale;

	private final Map<Class<?>, TranslationBundle> map = new ConcurrentHashMap<>();

	private NLS(Locale locale) {
		this.locale = locale;
	}

	@SuppressWarnings("unchecked")
	private <T extends TranslationBundle> T get(Class<T> type) {
		TranslationBundle bundle = map.get(type);
		if(bundle == null) {
			bundle = GlobalBundleCache.lookupBundle(locale, type);
			TranslationBundle old = map.putIfAbsent(type, bundle);
			if(old != null)
				bundle = old;
		}
		return (T) bundle;
	}
}
