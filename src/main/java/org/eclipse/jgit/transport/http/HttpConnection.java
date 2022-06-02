/*
 * Copyright (C) 2013, 2017 Christian Halstrick <christian.halstrick@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.transport.http;

import org.eclipse.jgit.annotations.NonNull;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.KeyManager;
import javax.net.ssl.TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProtocolException;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;

public interface HttpConnection {
	int HTTP_OK = java.net.HttpURLConnection.HTTP_OK;
	int HTTP_MOVED_PERM = java.net.HttpURLConnection.HTTP_MOVED_PERM;
	int HTTP_MOVED_TEMP = java.net.HttpURLConnection.HTTP_MOVED_TEMP;
	int HTTP_SEE_OTHER = java.net.HttpURLConnection.HTTP_SEE_OTHER;
	int HTTP_11_MOVED_TEMP = 307;
	int HTTP_11_MOVED_PERM = 308;
	int HTTP_NOT_FOUND = java.net.HttpURLConnection.HTTP_NOT_FOUND;
	int HTTP_UNAUTHORIZED = java.net.HttpURLConnection.HTTP_UNAUTHORIZED;
	int HTTP_FORBIDDEN = java.net.HttpURLConnection.HTTP_FORBIDDEN;

	int getResponseCode() throws IOException;

	URL getURL();

	String getResponseMessage() throws IOException;

	Map<String, List<String>> getHeaderFields();

	void setRequestProperty(String key, String value);

	void setRequestMethod(String method)
			throws ProtocolException;

	void setUseCaches(boolean usecaches);

	void setConnectTimeout(int timeout);

	void setReadTimeout(int timeout);

	String getContentType();

	InputStream getInputStream() throws IOException;

	String getHeaderField(@NonNull String name);

	List<String> getHeaderFields(@NonNull String name);

	int getContentLength();

	void setInstanceFollowRedirects(boolean followRedirects);

	void setDoOutput(boolean dooutput);

	void setFixedLengthStreamingMode(int contentLength);

	OutputStream getOutputStream() throws IOException;

	void setChunkedStreamingMode(int chunklen);

	String getRequestMethod();

	void configure(KeyManager[] km, TrustManager[] tm,
				   SecureRandom random) throws NoSuchAlgorithmException,
			KeyManagementException;

	void setHostnameVerifier(HostnameVerifier hostnameverifier)
			throws NoSuchAlgorithmException, KeyManagementException;
}
