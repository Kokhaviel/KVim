/*
 * Copyright (C) 2020 Thomas Wolf <thomas.wolf@paranor.ch> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.transport.http;

import java.io.IOException;
import java.security.GeneralSecurityException;

import org.eclipse.jgit.annotations.NonNull;

public interface HttpConnectionFactory2 extends HttpConnectionFactory {

	@NonNull
	GitSession newSession();

	interface GitSession {

		@NonNull
		HttpConnection configure(@NonNull HttpConnection connection,
								 boolean sslVerify) throws IOException, GeneralSecurityException;

		void close();
	}
}
