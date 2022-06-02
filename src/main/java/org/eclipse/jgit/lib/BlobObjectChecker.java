/*
 * Copyright (C) 2017, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.lib;

import org.eclipse.jgit.errors.CorruptObjectException;

public interface BlobObjectChecker {
	BlobObjectChecker NULL_CHECKER =
			new BlobObjectChecker() {
				@Override
				public void update(byte[] in, int p, int len) {
				}

				@Override
				public void endBlob(AnyObjectId id) {
				}
			};

	void update(byte[] in, int offset, int len);

	void endBlob(AnyObjectId id) throws CorruptObjectException;
}
