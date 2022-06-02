/*
 * Copyright (C) 2006, 2008, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org>
 * Copyright (C) 2010, 2020, Chris Aniszczyk <caniszczyk@gmail.com> and others
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

import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.util.References;

public class TagBuilder extends ObjectBuilder {

	private static final byte[] hobject = Constants.encodeASCII("object");
	private static final byte[] htype = Constants.encodeASCII("type");
	private static final byte[] htag = Constants.encodeASCII("tag");
	private static final byte[] htagger = Constants.encodeASCII("tagger");
	private ObjectId object;
	private int type = Constants.OBJ_BAD;
	private String tag;

	public int getObjectType() {
		return type;
	}

	public ObjectId getObjectId() {
		return object;
	}

	public void setObjectId(AnyObjectId obj, int objType) {
		object = obj.copy();
		type = objType;
	}

	public void setObjectId(RevObject obj) {
		setObjectId(obj, obj.getType());
	}

	public String getTag() {
		return tag;
	}

	public void setTag(String shortName) {
		this.tag = shortName;
	}

	public PersonIdent getTagger() {
		return getAuthor();
	}

	public void setTagger(PersonIdent taggerIdent) {
		setAuthor(taggerIdent);
	}

	@Override
	public byte[] build() throws UnsupportedEncodingException {
		ByteArrayOutputStream os = new ByteArrayOutputStream();
		try(OutputStreamWriter w = new OutputStreamWriter(os,
				getEncoding())) {

			os.write(hobject);
			os.write(' ');
			getObjectId().copyTo(os);
			os.write('\n');

			os.write(htype);
			os.write(' ');
			os.write(Constants
					.encodeASCII(Constants.typeString(getObjectType())));
			os.write('\n');

			os.write(htag);
			os.write(' ');
			w.write(getTag());
			w.flush();
			os.write('\n');

			if(getTagger() != null) {
				os.write(htagger);
				os.write(' ');
				w.write(getTagger().toExternalString());
				w.flush();
				os.write('\n');
			}

			writeEncoding(getEncoding(), os);

			os.write('\n');
			String msg = getMessage();
			if(msg != null) {
				w.write(msg);
				w.flush();
			}

			GpgSignature signature = getGpgSignature();
			if(signature != null) {
				if(msg != null && !msg.isEmpty() && !msg.endsWith("\n")) {
					throw new JGitInternalException(
							JGitText.get().signedTagMessageNoLf);
				}
				String externalForm = signature.toExternalString();
				w.write(externalForm);
				w.flush();
				if(!externalForm.endsWith("\n")) {
					os.write('\n');
				}
			}
		} catch(IOException err) {
			throw new RuntimeException(err);
		}
		return os.toByteArray();
	}

	@Override
	public String toString() {
		StringBuilder r = new StringBuilder();
		r.append("Tag");
		r.append("={\n");

		r.append("object ");
		r.append(object != null ? object.name() : "NOT_SET");
		r.append("\n");

		r.append("type ");
		r.append(object != null ? Constants.typeString(type) : "NOT_SET");
		r.append("\n");

		r.append("tag ");
		r.append(tag != null ? tag : "NOT_SET");
		r.append("\n");

		if(getTagger() != null) {
			r.append("tagger ");
			r.append(getTagger());
			r.append("\n");
		}

		Charset encoding = getEncoding();
		if(!References.isSameObject(encoding, UTF_8)) {
			r.append("encoding ");
			r.append(encoding.name());
			r.append("\n");
		}

		r.append("\n");
		r.append(getMessage() != null ? getMessage() : "");
		GpgSignature signature = getGpgSignature();
		r.append(signature != null ? signature.toExternalString() : "");
		r.append("}");
		return r.toString();
	}
}
