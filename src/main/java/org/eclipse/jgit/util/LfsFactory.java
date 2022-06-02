/*
 * Copyright (C) 2018, Markus Duft <markus.duft@ssi-schaefer.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.util;

import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.attributes.Attribute;
import org.eclipse.jgit.hooks.PrePushHook;
import org.eclipse.jgit.lib.ObjectLoader;

import java.io.IOException;
import java.io.InputStream;

public class LfsFactory {

	private static final LfsFactory instance = new LfsFactory();

	protected LfsFactory() {
	}

	public static LfsFactory getInstance() {
		return instance;
	}

	public boolean isAvailable() {
		return false;
	}

	public LfsInputStream applyCleanFilter(InputStream input, long length, Attribute attribute) {
		return new LfsInputStream(input, length);
	}

	public ObjectLoader applySmudgeFilter(ObjectLoader loader, Attribute attribute) {
		return loader;
	}

	@Nullable
	public PrePushHook getPrePushHook() {
		return null;
	}

	public static final class LfsInputStream extends InputStream {

		private final InputStream stream;
		private final long length;

		public LfsInputStream(InputStream stream, long length) {
			this.stream = stream;
			this.length = length;
		}

		@Override
		public void close() throws IOException {
			stream.close();
		}

		@Override
		public int read() throws IOException {
			return stream.read();
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			return stream.read(b, off, len);
		}

		public long getLength() {
			return length;
		}
	}

}
