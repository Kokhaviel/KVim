/*
 * Copyright (C) 2018, 2022 Salesforce and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.lib;

import java.util.Iterator;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.api.errors.CanceledException;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class GpgSigner {

	private static final Logger LOG = LoggerFactory.getLogger(GpgSigner.class);

	private static class DefaultSigner {

		private static volatile GpgSigner defaultSigner = loadGpgSigner();

		private static GpgSigner loadGpgSigner() {
			try {
				ServiceLoader<GpgSigner> loader = ServiceLoader
						.load(GpgSigner.class);
				Iterator<GpgSigner> iter = loader.iterator();
				if(iter.hasNext()) {
					return iter.next();
				}
			} catch(ServiceConfigurationError e) {
				LOG.error(e.getMessage(), e);
			}
			return null;
		}

		private DefaultSigner() {
		}

		public static GpgSigner getDefault() {
			return defaultSigner;
		}

		public static void setDefault(GpgSigner signer) {
			defaultSigner = signer;
		}
	}

	public static GpgSigner getDefault() {
		return DefaultSigner.getDefault();
	}

	public static void setDefault(GpgSigner signer) {
		DefaultSigner.setDefault(signer);
	}

	public abstract void sign(@NonNull CommitBuilder commit,
							  @Nullable String gpgSigningKey, @NonNull PersonIdent committer,
							  CredentialsProvider credentialsProvider) throws CanceledException;
}
