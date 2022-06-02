/*
 * Copyright (C) 2010, Google Inc.
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.util;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.ConnectException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.NoCheckX509TrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpSupport {

	private final static Logger LOG = LoggerFactory.getLogger(HttpSupport.class);
	public static final String METHOD_GET = "GET";
	public static final String METHOD_POST = "POST";
	public static final String HDR_PRAGMA = "Pragma";
	public static final String HDR_USER_AGENT = "User-Agent";
	public static final String HDR_SERVER = "Server";
	public static final String HDR_ACCEPT = "Accept";
	public static final String HDR_CONTENT_TYPE = "Content-Type";
	public static final String HDR_CONTENT_ENCODING = "Content-Encoding";
	public static final String HDR_ACCEPT_ENCODING = "Accept-Encoding";
	public static final String HDR_LOCATION = "Location";
	public static final String ENCODING_GZIP = "gzip";
	public static final String ENCODING_X_GZIP = "x-gzip";
	public static final String HDR_AUTHORIZATION = "Authorization";
	public static final String HDR_WWW_AUTHENTICATE = "WWW-Authenticate";
	public static final String HDR_COOKIE = "Cookie";
	public static final String HDR_SET_COOKIE = "Set-Cookie";
	public static final String HDR_SET_COOKIE2 = "Set-Cookie2";

	private static Set<String> configuredHttpsProtocols;

	public static void encode(StringBuilder urlstr, String key) {
		if(key == null || key.length() == 0)
			return;
		try {
			urlstr.append(URLEncoder.encode(key, UTF_8.name()));
		} catch(UnsupportedEncodingException e) {
			throw new RuntimeException(JGitText.get().couldNotURLEncodeToUTF8, e);
		}
	}

	public static int response(HttpConnection c) throws IOException {
		try {
			return c.getResponseCode();
		} catch(ConnectException ce) {
			final URL url = c.getURL();
			final String host = (url == null) ? "<null>" : url.getHost();
			if("Connection timed out: connect".equals(ce.getMessage()))
				throw new ConnectException(MessageFormat.format(JGitText.get().connectionTimeOut, host));
			throw new ConnectException(ce.getMessage() + " " + host);
		}
	}

	public static int response(java.net.HttpURLConnection c)
			throws IOException {
		try {
			return c.getResponseCode();
		} catch(ConnectException ce) {
			final URL url = c.getURL();
			final String host = (url == null) ? "<null>" : url.getHost();
			if("Connection timed out: connect".equals(ce.getMessage()))
				throw new ConnectException(MessageFormat.format(
						JGitText.get().connectionTimeOut, host));
			throw new ConnectException(ce.getMessage() + " " + host);
		}
	}

	public static Proxy proxyFor(ProxySelector proxySelector, URL u)
			throws ConnectException {
		try {
			URI uri = new URI(u.getProtocol(), null, u.getHost(), u.getPort(),
					null, null, null);
			return proxySelector.select(uri).get(0);
		} catch(URISyntaxException e) {
			final ConnectException err;
			err = new ConnectException(MessageFormat.format(JGitText.get().cannotDetermineProxyFor, u));
			err.initCause(e);
			throw err;
		}
	}

	public static void disableSslVerify(HttpConnection conn)
			throws IOException {
		TrustManager[] trustAllCerts = {
				new NoCheckX509TrustManager()};
		try {
			conn.configure(null, trustAllCerts, null);
			conn.setHostnameVerifier((name, session) -> true);
		} catch(KeyManagementException | NoSuchAlgorithmException e) {
			throw new IOException(e.getMessage(), e);
		}
	}

	public static void configureTLS(SSLSocket socket) {
		Set<String> enabled = new LinkedHashSet<>(
				Arrays.asList(socket.getEnabledProtocols()));
		for(String s : socket.getSupportedProtocols()) {
			if(s.startsWith("TLS")) {
				enabled.add(s);
			}
		}
		Set<String> configured = getConfiguredProtocols();
		if(!configured.isEmpty()) {
			enabled.retainAll(configured);
		}
		if(!enabled.isEmpty()) {
			socket.setEnabledProtocols(enabled.toArray(new String[0]));
		}
	}

	private static Set<String> getConfiguredProtocols() {
		Set<String> result = configuredHttpsProtocols;
		if(result == null) {
			String configured = getProperty();
			if(StringUtils.isEmptyOrNull(configured)) {
				result = Collections.emptySet();
			} else {
				result = new LinkedHashSet<>(
						Arrays.asList(configured.split("\\s*,\\s*")));
			}
			configuredHttpsProtocols = result;
		}
		return result;
	}

	private static String getProperty() {
		try {
			return SystemReader.getInstance().getProperty("https.protocols");
		} catch(SecurityException e) {
			LOG.warn(JGitText.get().failedReadHttpsProtocols, e);
			return null;
		}
	}

	public static int scanToken(String header, int from) {
		int length = header.length();
		int i = from;
		if(i < 0 || i > length) {
			throw new IndexOutOfBoundsException();
		}
		while(i < length) {
			char c = header.charAt(i);
			switch(c) {
				case '!':
				case '#':
				case '$':
				case '%':
				case '&':
				case '\'':
				case '*':
				case '+':
				case '-':
				case '.':
				case '^':
				case '_':
				case '`':
				case '|':
				case '~':
				case '0':
				case '1':
				case '2':
				case '3':
				case '4':
				case '5':
				case '6':
				case '7':
				case '8':
				case '9':
					i++;
					break;
				default:
					if((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
						i++;
						break;
					}
					return i;
			}
		}
		return i;
	}

	private HttpSupport() {
	}
}
