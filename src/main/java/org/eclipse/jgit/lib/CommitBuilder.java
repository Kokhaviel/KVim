/*
 * Copyright (C) 2007, Dave Watson <dwatson@mimvista.com>
 * Copyright (C) 2006, 2007, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2006, 2020, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.lib;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.List;

import org.eclipse.jgit.util.References;

public class CommitBuilder extends ObjectBuilder {
	private static final ObjectId[] EMPTY_OBJECTID_LIST = new ObjectId[0];

	private static final byte[] htree = Constants.encodeASCII("tree");
	private static final byte[] hparent = Constants.encodeASCII("parent");
	private static final byte[] hauthor = Constants.encodeASCII("author");
	private static final byte[] hcommitter = Constants.encodeASCII("committer");
	private static final byte[] hgpgsig = Constants.encodeASCII("gpgsig");

	private ObjectId treeId;
	private ObjectId[] parentIds;
	private PersonIdent committer;

	public CommitBuilder() {
		parentIds = EMPTY_OBJECTID_LIST;
	}

	public ObjectId getTreeId() {
		return treeId;
	}

	public void setTreeId(AnyObjectId id) {
		treeId = id.copy();
	}

	@Override
	public PersonIdent getAuthor() {
		return super.getAuthor();
	}

	@Override
	public void setAuthor(PersonIdent newAuthor) {
		super.setAuthor(newAuthor);
	}

	public PersonIdent getCommitter() {
		return committer;
	}

	public void setCommitter(PersonIdent newCommitter) {
		committer = newCommitter;
	}

	public ObjectId[] getParentIds() {
		return parentIds;
	}

	public void setParentId(AnyObjectId newParent) {
		parentIds = new ObjectId[] {newParent.copy()};
	}

	public void setParentIds(ObjectId... newParents) {
		parentIds = new ObjectId[newParents.length];
		for(int i = 0; i < newParents.length; i++)
			parentIds[i] = newParents[i].copy();
	}

	public void setParentIds(List<? extends AnyObjectId> newParents) {
		parentIds = new ObjectId[newParents.size()];
		for(int i = 0; i < newParents.size(); i++)
			parentIds[i] = newParents.get(i).copy();
	}

	public void addParentId(AnyObjectId additionalParent) {
		if(parentIds.length == 0) {
			setParentId(additionalParent);
		} else {
			ObjectId[] newParents = new ObjectId[parentIds.length + 1];
			System.arraycopy(parentIds, 0, newParents, 0, parentIds.length);
			newParents[parentIds.length] = additionalParent.copy();
			parentIds = newParents;
		}
	}

	@Deprecated
	public void setEncoding(String encodingName) {
		setEncoding(Charset.forName(encodingName));
	}

	@Override
	public byte[] build() throws UnsupportedEncodingException {
		ByteArrayOutputStream os = new ByteArrayOutputStream();
		OutputStreamWriter w = new OutputStreamWriter(os, getEncoding());
		try {
			os.write(htree);
			os.write(' ');
			getTreeId().copyTo(os);
			os.write('\n');

			for(ObjectId p : getParentIds()) {
				os.write(hparent);
				os.write(' ');
				p.copyTo(os);
				os.write('\n');
			}

			os.write(hauthor);
			os.write(' ');
			w.write(getAuthor().toExternalString());
			w.flush();
			os.write('\n');

			os.write(hcommitter);
			os.write(' ');
			w.write(getCommitter().toExternalString());
			w.flush();
			os.write('\n');

			GpgSignature signature = getGpgSignature();
			if(signature != null) {
				os.write(hgpgsig);
				os.write(' ');
				writeMultiLineHeader(signature.toExternalString(), os
				);
				os.write('\n');
			}

			writeEncoding(getEncoding(), os);

			os.write('\n');

			if(getMessage() != null) {
				w.write(getMessage());
				w.flush();
			}
		} catch(IOException err) {
			throw new RuntimeException(err);
		}
		return os.toByteArray();
	}

	public byte[] toByteArray() throws UnsupportedEncodingException {
		return build();
	}

	@Override
	public String toString() {
		StringBuilder r = new StringBuilder();
		r.append("Commit");
		r.append("={\n");

		r.append("tree ");
		r.append(treeId != null ? treeId.name() : "NOT_SET");
		r.append("\n");

		for(ObjectId p : parentIds) {
			r.append("parent ");
			r.append(p.name());
			r.append("\n");
		}

		r.append("author ");
		r.append(getAuthor() != null ? getAuthor().toString() : "NOT_SET");
		r.append("\n");

		r.append("committer ");
		r.append(committer != null ? committer.toString() : "NOT_SET");
		r.append("\n");

		r.append("gpgSignature ");
		GpgSignature signature = getGpgSignature();
		r.append(signature != null ? signature.toString()
				: "NOT_SET");
		r.append("\n");

		Charset encoding = getEncoding();
		if(!References.isSameObject(encoding, UTF_8)) {
			r.append("encoding ");
			r.append(encoding.name());
			r.append("\n");
		}

		r.append("\n");
		r.append(getMessage() != null ? getMessage() : "");
		r.append("}");
		return r.toString();
	}
}
