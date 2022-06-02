/*
 * Copyright (C) 2010, Google Inc. and others
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
import java.util.List;

import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.StoredObjectRepresentationNotAvailableException;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.BitmapIndex.BitmapBuilder;
import org.eclipse.jgit.lib.ProgressMonitor;

public interface ObjectReuseAsIs {

	ObjectToPack newObjectToPack(AnyObjectId objectId, int type);

	void selectObjectRepresentation(PackWriter packer, ProgressMonitor monitor, Iterable<ObjectToPack> objects)
			throws IOException;

	void writeObjects(PackOutputStream out, List<ObjectToPack> list)
			throws IOException;

	void copyObjectAsIs(PackOutputStream out, ObjectToPack otp,
						boolean validate) throws IOException,
			StoredObjectRepresentationNotAvailableException;

	void copyPackAsIs(PackOutputStream out, CachedPack pack)
			throws IOException;

	Collection<CachedPack> getCachedPacksAndUpdate(
			BitmapBuilder needBitmap) throws IOException;
}
