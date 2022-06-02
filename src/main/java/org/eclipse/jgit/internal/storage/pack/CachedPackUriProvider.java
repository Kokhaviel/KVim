/*
 * Copyright (C) 2019, Google LLC. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.internal.storage.pack;

import java.io.IOException;
import java.util.Collection;

import org.eclipse.jgit.annotations.Nullable;

public interface CachedPackUriProvider {

	@Nullable
	PackInfo getInfo(CachedPack pack, Collection<String> protocolsSupported) throws IOException;

	class PackInfo {
		private final String hash;
		private final String uri;
		private final long size;

		public PackInfo(String hash, String uri, long size) {
			this.hash = hash;
			this.uri = uri;
			this.size = size;
		}

		public String getHash() {
			return hash;
		}

		public String getUri() {
			return uri;
		}

		public long getSize() {
			return size;
		}
	}
}
