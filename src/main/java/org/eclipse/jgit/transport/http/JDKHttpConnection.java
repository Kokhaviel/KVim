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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;

import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.internal.transport.http.DelegatingSSLSocketFactory;
import org.eclipse.jgit.util.HttpSupport;

public class JDKHttpConnection implements HttpConnection {
	HttpURLConnection wrappedUrlConnection;

	protected JDKHttpConnection(URL url)
			throws
			IOException {
		this.wrappedUrlConnection = (HttpURLConnection) url.openConnection();
	}

	protected JDKHttpConnection(URL url, Proxy proxy)
			throws IOException {
		this.wrappedUrlConnection = (HttpURLConnection) url
				.openConnection(proxy);
	}

	@Override
	public int getResponseCode() throws IOException {
		return wrappedUrlConnection.getResponseCode();
	}

	@Override
	public URL getURL() {
		return wrappedUrlConnection.getURL();
	}

	@Override
	public String getResponseMessage() throws IOException {
		return wrappedUrlConnection.getResponseMessage();
	}

	@Override
	public Map<String, List<String>> getHeaderFields() {
		return wrappedUrlConnection.getHeaderFields();
	}

	@Override
	public void setRequestProperty(String key, String value) {
		wrappedUrlConnection.setRequestProperty(key, value);
	}

	@Override
	public void setRequestMethod(String method) throws ProtocolException {
		wrappedUrlConnection.setRequestMethod(method);
	}

	@Override
	public void setUseCaches(boolean usecaches) {
		wrappedUrlConnection.setUseCaches(usecaches);
	}

	@Override
	public void setConnectTimeout(int timeout) {
		wrappedUrlConnection.setConnectTimeout(timeout);
	}

	@Override
	public void setReadTimeout(int timeout) {
		wrappedUrlConnection.setReadTimeout(timeout);
	}

	@Override
	public String getContentType() {
		return wrappedUrlConnection.getContentType();
	}

	@Override
	public InputStream getInputStream() throws IOException {
		return wrappedUrlConnection.getInputStream();
	}

	@Override
	public String getHeaderField(@NonNull String name) {
		return wrappedUrlConnection.getHeaderField(name);
	}

	@Override
	public List<String> getHeaderFields(@NonNull String name) {
		Map<String, List<String>> m = wrappedUrlConnection.getHeaderFields();
		return mapValuesToListIgnoreCase(name, m);
	}

	private static List<String> mapValuesToListIgnoreCase(String keyName,
														  Map<String, List<String>> m) {
		List<String> fields = new LinkedList<>();
		m.entrySet().stream().filter(e -> keyName.equalsIgnoreCase(e.getKey()))
				.filter(e -> e.getValue() != null)
				.forEach(e -> fields.addAll(e.getValue()));
		return fields;
	}

	@Override
	public int getContentLength() {
		return wrappedUrlConnection.getContentLength();
	}

	@Override
	public void setInstanceFollowRedirects(boolean followRedirects) {
		wrappedUrlConnection.setInstanceFollowRedirects(followRedirects);
	}

	@Override
	public void setDoOutput(boolean dooutput) {
		wrappedUrlConnection.setDoOutput(dooutput);
	}

	@Override
	public void setFixedLengthStreamingMode(int contentLength) {
		wrappedUrlConnection.setFixedLengthStreamingMode(contentLength);
	}

	@Override
	public OutputStream getOutputStream() throws IOException {
		return wrappedUrlConnection.getOutputStream();
	}

	@Override
	public void setChunkedStreamingMode(int chunklen) {
		wrappedUrlConnection.setChunkedStreamingMode(chunklen);
	}

	@Override
	public String getRequestMethod() {
		return wrappedUrlConnection.getRequestMethod();
	}

	@Override
	public void setHostnameVerifier(HostnameVerifier hostnameverifier) {
		((HttpsURLConnection) wrappedUrlConnection)
				.setHostnameVerifier(hostnameverifier);
	}

	@Override
	public void configure(KeyManager[] km, TrustManager[] tm,
						  SecureRandom random) throws NoSuchAlgorithmException,
			KeyManagementException {
		SSLContext ctx = SSLContext.getInstance("TLS");
		ctx.init(km, tm, random);
		((HttpsURLConnection) wrappedUrlConnection).setSSLSocketFactory(
				new DelegatingSSLSocketFactory(ctx.getSocketFactory()) {

					@Override
					protected void configure(SSLSocket socket) {
						HttpSupport.configureTLS(socket);
					}
				});
	}

}
