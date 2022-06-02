/*
 * Copyright (C) 2014 Laurent Goujon <lgoujon@twitter.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.util;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;

import org.ietf.jgss.GSSManager;

public abstract class GSSManagerFactory {

	public static GSSManagerFactory detect() {
		return (SunGSSManagerFactory.isSupported()) ? new SunGSSManagerFactory() : new DefaultGSSManagerFactory();
	}

	public abstract GSSManager newInstance(URL url);

	private static class DefaultGSSManagerFactory extends GSSManagerFactory {
		private static final GSSManager INSTANCE = GSSManager.getInstance();

		@Override
		public GSSManager newInstance(URL url) {
			return INSTANCE;
		}
	}

	private static class SunGSSManagerFactory extends GSSManagerFactory {
		private static boolean IS_SUPPORTED;
		private static Constructor<?> HTTP_CALLER_INFO_CONSTRUCTOR;
		private static Constructor<?> HTTP_CALLER_CONSTRUCTOR;

		private static Constructor<?> GSS_MANAGER_IMPL_CONSTRUCTOR;

		static {
			try {
				init();
				IS_SUPPORTED = true;
			} catch(Exception e) {
				IS_SUPPORTED = false;
			}
		}

		private static void init() throws ClassNotFoundException,
				NoSuchMethodException {
			Class<?> httpCallerInfoClazz = Class
					.forName("sun.net.www.protocol.http.HttpCallerInfo");
			HTTP_CALLER_INFO_CONSTRUCTOR = httpCallerInfoClazz
					.getConstructor(URL.class);

			Class<?> httpCallerClazz = Class
					.forName("sun.security.jgss.HttpCaller");
			HTTP_CALLER_CONSTRUCTOR = httpCallerClazz
					.getConstructor(httpCallerInfoClazz);

			Class<?> gssCallerClazz = Class
					.forName("sun.security.jgss.GSSCaller");
			Class<?> gssManagerImplClazz = Class
					.forName("sun.security.jgss.GSSManagerImpl");
			GSS_MANAGER_IMPL_CONSTRUCTOR = gssManagerImplClazz
					.getConstructor(gssCallerClazz);

		}

		public static boolean isSupported() {
			return IS_SUPPORTED;
		}

		@Override
		public GSSManager newInstance(URL url) {
			try {
				Object httpCallerInfo = HTTP_CALLER_INFO_CONSTRUCTOR
						.newInstance(url);
				Object httpCaller = HTTP_CALLER_CONSTRUCTOR
						.newInstance(httpCallerInfo);

				return (GSSManager) GSS_MANAGER_IMPL_CONSTRUCTOR
						.newInstance(httpCaller);
			} catch(InstantiationException | IllegalAccessException
					| IllegalArgumentException | InvocationTargetException e) {
				throw new Error(e);
			}
		}
	}
}
