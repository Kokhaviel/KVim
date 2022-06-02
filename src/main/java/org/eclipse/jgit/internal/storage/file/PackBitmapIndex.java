/*
 * Copyright (C) 2012, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.file;

import com.googlecode.javaewah.EWAHCompressedBitmap;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.util.io.SilentFileInputStream;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.MessageFormat;

public abstract class PackBitmapIndex {

	public static final int FLAG_REUSE = 1;

	public static PackBitmapIndex open(File idxFile, PackIndex packIndex,
									   PackReverseIndex reverseIndex)
			throws IOException {
		try(SilentFileInputStream fd = new SilentFileInputStream(idxFile)) {
			try {
				return read(fd, packIndex, reverseIndex);
			} catch(IOException ioe) {
				throw new IOException(
						MessageFormat.format(JGitText.get().unreadablePackIndex,
								idxFile.getAbsolutePath()),
						ioe);
			}
		}
	}

	public static PackBitmapIndex read(InputStream fd, PackIndex packIndex,
									   PackReverseIndex reverseIndex) throws IOException {
		return new PackBitmapIndexV1(fd, packIndex, reverseIndex);
	}

	public static PackBitmapIndex read(InputStream fd,
									   SupplierWithIOException<PackIndex> packIndexSupplier,
									   SupplierWithIOException<PackReverseIndex> reverseIndexSupplier,
									   boolean loadParallelRevIndex)
			throws IOException {
		return new PackBitmapIndexV1(fd, packIndexSupplier,
				reverseIndexSupplier, loadParallelRevIndex);
	}

	byte[] packChecksum;

	public abstract int findPosition(AnyObjectId objectId);

	public abstract ObjectId getObject(int position) throws IllegalArgumentException;

	public abstract EWAHCompressedBitmap ofObjectType(EWAHCompressedBitmap bitmap, int type);

	public abstract EWAHCompressedBitmap getBitmap(AnyObjectId objectId);

	public abstract int getObjectCount();

	public abstract int getBitmapCount();

	@FunctionalInterface
	public interface SupplierWithIOException<T> {

		T get() throws IOException;
	}
}
