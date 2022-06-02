/*
 * Copyright (C) 2008, Charles O'Farrell <charleso@charleso.org>
 * Copyright (C) 2009-2010, Google Inc.
 * Copyright (C) 2009, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.lib;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Collection;

import org.eclipse.jgit.internal.storage.file.RefDirectory;
import org.eclipse.jgit.util.RefList;

public abstract class RefWriter {

	private final Collection<Ref> refs;

	public RefWriter(Collection<Ref> refs) {
		this.refs = RefComparator.sort(refs);
	}

	public RefWriter(RefList<Ref> refs) {
		this.refs = refs.asList();
	}

	public void writeInfoRefs() throws IOException {
		final StringWriter w = new StringWriter();
		final char[] tmp = new char[Constants.OBJECT_ID_STRING_LENGTH];
		for(Ref r : refs) {
			if(Constants.HEAD.equals(r.getName())) {
				continue;
			}

			ObjectId objectId = r.getObjectId();
			if(objectId == null) {
				continue;
			}
			objectId.copyTo(tmp, w);
			w.write('\t');
			w.write(r.getName());
			w.write('\n');

			ObjectId peeledObjectId = r.getPeeledObjectId();
			if(peeledObjectId != null) {
				peeledObjectId.copyTo(tmp, w);
				w.write('\t');
				w.write(r.getName());
				w.write("^{}\n");
			}
		}
		writeFile(Constants.INFO_REFS, Constants.encode(w.toString()));
	}

	public void writePackedRefs() throws IOException {
		boolean peeled = false;
		for(Ref r : refs) {
			if(r.getStorage().isPacked() && r.isPeeled()) {
				peeled = true;
				break;
			}
		}

		final StringWriter w = new StringWriter();
		if(peeled) {
			w.write(RefDirectory.PACKED_REFS_HEADER);
			w.write(RefDirectory.PACKED_REFS_PEELED);
			w.write('\n');
		}

		final char[] tmp = new char[Constants.OBJECT_ID_STRING_LENGTH];
		for(Ref r : refs) {
			if(r.getStorage() != Ref.Storage.PACKED)
				continue;

			ObjectId objectId = r.getObjectId();
			if(objectId == null) {
				throw new NullPointerException();
			}
			objectId.copyTo(tmp, w);
			w.write(' ');
			w.write(r.getName());
			w.write('\n');

			ObjectId peeledObjectId = r.getPeeledObjectId();
			if(peeledObjectId != null) {
				w.write('^');
				peeledObjectId.copyTo(tmp, w);
				w.write('\n');
			}
		}
		writeFile(Constants.PACKED_REFS, Constants.encode(w.toString()));
	}

	protected abstract void writeFile(String file, byte[] content)
			throws IOException;
}
