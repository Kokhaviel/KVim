/*
 * Copyright (C) 2009, Constantine Plotnikov <constantine.plotnikov@gmail.com>
 * Copyright (C) 2009, Google Inc.
 * Copyright (C) 2009, JetBrains s.r.o. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.lib;

import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.util.RawParseUtils;

import java.io.IOException;

import static java.nio.charset.StandardCharsets.UTF_8;

public class BlobBasedConfig extends Config {

	public BlobBasedConfig(Config base, byte[] blob)
			throws ConfigInvalidException {
		super(base);
		final String decoded;
		if(isUtf8(blob)) {
			decoded = RawParseUtils.decode(UTF_8, blob, 3, blob.length);
		} else {
			decoded = RawParseUtils.decode(blob);
		}
		fromText(decoded);
	}

	public BlobBasedConfig(Config base, Repository db, AnyObjectId objectId)
			throws IOException, ConfigInvalidException {
		this(base, read(db, objectId));
	}

	private static byte[] read(Repository db, AnyObjectId blobId) throws IOException {
		try(ObjectReader or = db.newObjectReader()) {
			return read(or, blobId);
		}
	}

	private static byte[] read(ObjectReader or, AnyObjectId blobId) throws IOException {
		ObjectLoader loader = or.open(blobId, Constants.OBJ_BLOB);
		return loader.getCachedBytes(Integer.MAX_VALUE);
	}

}
