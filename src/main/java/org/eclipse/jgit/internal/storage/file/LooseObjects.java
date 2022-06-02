/*
 * Copyright (C) 2009, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.file;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardCopyOption;
import java.text.MessageFormat;
import java.util.Set;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.internal.storage.file.FileObjectDatabase.InsertLooseObjectResult;
import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class LooseObjects {
	private static final Logger LOG = LoggerFactory.getLogger(LooseObjects.class);
	private final static int MAX_LOOSE_OBJECT_STALE_READ_ATTEMPTS = 5;
	private final File directory;
	private final UnpackedObjectCache unpackedObjectCache;

	LooseObjects(File dir) {
		directory = dir;
		unpackedObjectCache = new UnpackedObjectCache();
	}

	File getDirectory() {
		return directory;
	}

	void create() throws IOException {
		FileUtils.mkdirs(directory);
	}

	void close() {
		unpackedObjectCache().clear();
	}

	@Override
	public String toString() {
		return "LooseObjects[" + directory + "]";
	}

	boolean hasCached(AnyObjectId id) {
		return unpackedObjectCache().isUnpacked(id);
	}

	boolean has(AnyObjectId objectId) {
		return fileFor(objectId).exists();
	}

	boolean resolve(Set<ObjectId> matches, AbbreviatedObjectId id, int matchLimit) {
		String fanOut = id.name().substring(0, 2);
		String[] entries = new File(directory, fanOut).list();
		if(entries != null) {
			for(String e : entries) {
				if(e.length() != Constants.OBJECT_ID_STRING_LENGTH - 2) {
					continue;
				}
				try {
					ObjectId entId = ObjectId.fromString(fanOut + e);
					if(id.prefixCompare(entId) == 0) {
						matches.add(entId);
					}
				} catch(IllegalArgumentException notId) {
					continue;
				}
				if(matches.size() > matchLimit) {
					return false;
				}
			}
		}
		return true;
	}

	ObjectLoader open(WindowCursor curs, AnyObjectId id) throws IOException {
		int readAttempts = 0;
		while(readAttempts < MAX_LOOSE_OBJECT_STALE_READ_ATTEMPTS) {
			readAttempts++;
			File path = fileFor(id);
			try {
				return getObjectLoader(curs, path, id);
			} catch(FileNotFoundException noFile) {
				if(path.exists()) {
					throw noFile;
				}
				break;
			} catch(IOException e) {
				if(!FileUtils.isStaleFileHandleInCausalChain(e)) {
					throw e;
				}
				if(LOG.isDebugEnabled()) {
					LOG.debug(MessageFormat.format(JGitText.get().looseObjectHandleIsStale, id.name(),
							readAttempts, MAX_LOOSE_OBJECT_STALE_READ_ATTEMPTS));
				}
			}
		}
		unpackedObjectCache().remove(id);
		return null;
	}

	ObjectLoader getObjectLoader(WindowCursor curs, File path, AnyObjectId id)
			throws IOException {
		try(FileInputStream in = new FileInputStream(path)) {
			unpackedObjectCache().add(id);
			return UnpackedObject.open(in, path, id, curs);
		}
	}

	UnpackedObjectCache unpackedObjectCache() {
		return unpackedObjectCache;
	}

	long getSize(WindowCursor curs, AnyObjectId id) throws IOException {
		File f = fileFor(id);
		try(FileInputStream in = new FileInputStream(f)) {
			unpackedObjectCache().add(id);
			return UnpackedObject.getSize(in, id, curs);
		} catch(FileNotFoundException noFile) {
			if(f.exists()) {
				throw noFile;
			}
			unpackedObjectCache().remove(id);
			return -1;
		}
	}

	InsertLooseObjectResult insert(File tmp, ObjectId id) throws IOException {
		final File dst = fileFor(id);
		if(dst.exists()) {
			FileUtils.delete(tmp, FileUtils.RETRY);
			return InsertLooseObjectResult.EXISTS_LOOSE;
		}

		try {
			return tryMove(tmp, dst, id);
		} catch(NoSuchFileException e) {
			FileUtils.mkdir(dst.getParentFile(), true);
		} catch(IOException e) {
			LOG.error(e.getMessage(), e);
			FileUtils.delete(tmp, FileUtils.RETRY);
			return InsertLooseObjectResult.FAILURE;
		}

		try {
			return tryMove(tmp, dst, id);
		} catch(IOException e) {
			LOG.error(e.getMessage(), e);
			FileUtils.delete(tmp, FileUtils.RETRY);
			return InsertLooseObjectResult.FAILURE;
		}
	}

	private InsertLooseObjectResult tryMove(File tmp, File dst, ObjectId id)
			throws IOException {
		Files.move(FileUtils.toPath(tmp), FileUtils.toPath(dst),
				StandardCopyOption.ATOMIC_MOVE);
		dst.setReadOnly();
		unpackedObjectCache().add(id);
		return InsertLooseObjectResult.INSERTED;
	}

	File fileFor(AnyObjectId objectId) {
		String n = objectId.name();
		String d = n.substring(0, 2);
		String f = n.substring(2);
		return new File(new File(getDirectory(), d), f);
	}
}
