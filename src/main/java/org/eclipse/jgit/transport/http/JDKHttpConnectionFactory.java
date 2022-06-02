/*
 * Copyright (C) 2013, 2020 Christian Halstrick <christian.halstrick@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.transport.http;

import java.io.IOException;
import java.net.Proxy;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.text.MessageFormat;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.internal.transport.http.DelegatingSSLSocketFactory;
import org.eclipse.jgit.util.HttpSupport;

public class JDKHttpConnectionFactory implements HttpConnectionFactory2 {

	@Override
	public HttpConnection create(URL url) throws IOException {
		return new JDKHttpConnection(url);
	}

	@Override
	public HttpConnection create(URL url, Proxy proxy)
			throws IOException {
		return new JDKHttpConnection(url, proxy);
	}

	@Override
	public GitSession newSession() {
		return new JdkConnectionSession();
	}

	private static class JdkConnectionSession implements GitSession {

		private SSLContext securityContext;

		private SSLSocketFactory socketFactory;

		@Override
		public JDKHttpConnection configure(HttpConnection connection,
										   boolean sslVerify) throws GeneralSecurityException {
			if(!(connection instanceof JDKHttpConnection)) {
				throw new IllegalArgumentException(MessageFormat.format(
						JGitText.get().httpWrongConnectionType,
						JDKHttpConnection.class.getName(),
						connection.getClass().getName()));
			}
			JDKHttpConnection conn = (JDKHttpConnection) connection;
			String scheme = conn.getURL().getProtocol();
			if(!"https".equals(scheme) || sslVerify) {
				return conn;
			}
			if(securityContext == null) {
				securityContext = SSLContext.getInstance("TLS");
				TrustManager[] trustAllCerts = {
						new NoCheckX509TrustManager()};
				securityContext.init(null, trustAllCerts, null);
				socketFactory = new DelegatingSSLSocketFactory(
						securityContext.getSocketFactory()) {

					@Override
					protected void configure(SSLSocket socket) {
						HttpSupport.configureTLS(socket);
					}
				};
			}
			conn.setHostnameVerifier((name, session) -> true);
			((HttpsURLConnection) conn.wrappedUrlConnection)
					.setSSLSocketFactory(socketFactory);
			return conn;
		}

		@Override
		public void close() {
			securityContext = null;
			socketFactory = null;
		}
	}

}
