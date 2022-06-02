/*
 * Copyright (C) 2018, Thomas Wolf <thomas.wolf@paranor.ch> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.transport;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

public interface FtpChannel {

	class FtpException extends IOException {

		private static final long serialVersionUID = 7176525179280330876L;

		public static final int OK = 0;
		public static final int NO_SUCH_FILE = 2;
		private final int status;

		public FtpException(String message, int status) {
			super(message);
			this.status = status;
		}

		public int getStatus() {
			return status;
		}
	}

	void connect(int timeout, TimeUnit unit) throws IOException;

	void disconnect();

	boolean isConnected();

	void cd(String path) throws IOException;

	String pwd() throws IOException;

	interface DirEntry {
		String getFilename();

		long getModifiedTime();

		boolean isDirectory();
	}

	Collection<DirEntry> ls(String path) throws IOException;

	void rmdir(String path) throws IOException;

	void mkdir(String path) throws IOException;

	InputStream get(String path) throws IOException;

	OutputStream put(String path) throws IOException;

	void rm(String path) throws IOException;

	default void delete(String path) throws IOException {
		try {
			rm(path);
		} catch(FileNotFoundException ignored) {
		} catch(FtpException f) {
			if(f.getStatus() == FtpException.NO_SUCH_FILE) {
				return;
			}
			throw f;
		}
	}

	void rename(String from, String to) throws IOException;
}
