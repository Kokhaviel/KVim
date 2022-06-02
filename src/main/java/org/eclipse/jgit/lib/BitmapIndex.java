/*
 * Copyright (C) 2012, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.lib;

import java.util.Iterator;

import org.eclipse.jgit.internal.storage.file.PackBitmapIndex;

import com.googlecode.javaewah.EWAHCompressedBitmap;

public interface BitmapIndex {

	Bitmap getBitmap(AnyObjectId objectId);

	BitmapBuilder newBitmapBuilder();

	interface Bitmap extends Iterable<BitmapObject> {

		Bitmap or(Bitmap other);

		Bitmap andNot(Bitmap other);

		@Override
		Iterator<BitmapObject> iterator();

		EWAHCompressedBitmap retrieveCompressed();
	}

	interface BitmapBuilder extends Bitmap {

		boolean contains(AnyObjectId objectId);

		BitmapBuilder addObject(AnyObjectId objectId, int type);

		void remove(AnyObjectId objectId);

		@Override
		BitmapBuilder or(Bitmap other);

		@Override
		BitmapBuilder andNot(Bitmap other);

		Bitmap build();

		boolean removeAllOrNone(PackBitmapIndex bitmapIndex);

		int cardinality();

		BitmapIndex getBitmapIndex();
	}
}
