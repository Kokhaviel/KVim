/*
 * Copyright (C) 2010, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.lib;

import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.util.TemporaryBuffer;

import java.io.IOException;

import static org.eclipse.jgit.lib.Constants.OBJECT_ID_LENGTH;
import static org.eclipse.jgit.lib.Constants.OBJ_TREE;

public class TreeFormatter {

	public static int entrySize(FileMode mode, int nameLen) {
		return mode.copyToLength() + nameLen + OBJECT_ID_LENGTH + 2;
	}

	private byte[] buf;
	private int ptr;
	private TemporaryBuffer.Heap overflowBuffer;

	public TreeFormatter(int size) {
		buf = new byte[size];
	}

	public void append(byte[] name, FileMode mode, AnyObjectId id) {
		append(name, 0, name.length, mode, id);
	}

	public void append(byte[] nameBuf, int namePos, int nameLen, FileMode mode,
					   AnyObjectId id) {
		append(nameBuf, namePos, nameLen, mode, id, false);
	}

	public void append(byte[] nameBuf, int namePos, int nameLen, FileMode mode,
					   AnyObjectId id, boolean allowEmptyName) {
		if(nameLen == 0 && !allowEmptyName) {
			throw new IllegalArgumentException(
					JGitText.get().invalidTreeZeroLengthName);
		}
		if(fmtBuf(nameBuf, namePos, nameLen, mode)) {
			id.copyRawTo(buf, ptr);
			ptr += OBJECT_ID_LENGTH;

		} else {
			try {
				fmtOverflowBuffer(nameBuf, namePos, nameLen, mode);
				id.copyRawTo(overflowBuffer);
			} catch(IOException badBuffer) {
				throw new RuntimeException(badBuffer);
			}
		}
	}

	public void append(byte[] nameBuf, int namePos, int nameLen, FileMode mode,
					   byte[] idBuf, int idPos) {
		if(fmtBuf(nameBuf, namePos, nameLen, mode)) {
			System.arraycopy(idBuf, idPos, buf, ptr, OBJECT_ID_LENGTH);
			ptr += OBJECT_ID_LENGTH;

		} else {
			try {
				fmtOverflowBuffer(nameBuf, namePos, nameLen, mode);
				overflowBuffer.write(idBuf, idPos, OBJECT_ID_LENGTH);
			} catch(IOException badBuffer) {
				throw new RuntimeException(badBuffer);
			}
		}
	}

	private boolean fmtBuf(byte[] nameBuf, int namePos, int nameLen,
						   FileMode mode) {
		if(buf == null || buf.length < ptr + entrySize(mode, nameLen))
			return false;

		mode.copyTo(buf, ptr);
		ptr += mode.copyToLength();
		buf[ptr++] = ' ';

		System.arraycopy(nameBuf, namePos, buf, ptr, nameLen);
		ptr += nameLen;
		buf[ptr++] = 0;
		return true;
	}

	private void fmtOverflowBuffer(byte[] nameBuf, int namePos, int nameLen,
								   FileMode mode) throws IOException {
		if(buf != null) {
			overflowBuffer = new TemporaryBuffer.Heap(Integer.MAX_VALUE);
			overflowBuffer.write(buf, 0, ptr);
			buf = null;
		}

		mode.copyTo(overflowBuffer);
		overflowBuffer.write((byte) ' ');
		overflowBuffer.write(nameBuf, namePos, nameLen);
		overflowBuffer.write((byte) 0);
	}

	public ObjectId insertTo(ObjectInserter ins) throws IOException {
		if(buf != null)
			return ins.insert(OBJ_TREE, buf, 0, ptr);

		final long len = overflowBuffer.length();
		return ins.insert(OBJ_TREE, len, overflowBuffer.openInputStream());
	}

	public ObjectId computeId(ObjectInserter ins) {
		if(buf != null)
			return ins.idFor(OBJ_TREE, buf, 0, ptr);

		final long len = overflowBuffer.length();
		try {
			return ins.idFor(OBJ_TREE, len, overflowBuffer.openInputStream());
		} catch(IOException e) {
			throw new RuntimeException(e);
		}
	}

	public byte[] toByteArray() {
		if(buf != null) {
			byte[] r = new byte[ptr];
			System.arraycopy(buf, 0, r, 0, ptr);
			return r;
		}

		try {
			return overflowBuffer.toByteArray();
		} catch(IOException err) {
			throw new RuntimeException(err);
		}
	}

	@Override
	public String toString() {
		byte[] raw = toByteArray();

		CanonicalTreeParser p = new CanonicalTreeParser();
		p.reset(raw);

		StringBuilder r = new StringBuilder();
		r.append("Tree={");
		if(!p.eof()) {
			r.append('\n');
			try {
				new ObjectChecker().checkTree(raw);
			} catch(CorruptObjectException error) {
				r.append("*** ERROR: ").append(error.getMessage()).append("\n");
				r.append('\n');
			}
		}
		while(!p.eof()) {
			final FileMode mode = p.getEntryFileMode();
			r.append(mode);
			r.append(' ');
			r.append(Constants.typeString(mode.getObjectType()));
			r.append(' ');
			r.append(p.getEntryObjectId().name());
			r.append(' ');
			r.append(p.getEntryPathString());
			r.append('\n');
			p.next();
		}
		r.append("}");
		return r.toString();
	}
}
