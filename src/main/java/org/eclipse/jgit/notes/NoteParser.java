/*
 * Copyright (C) 2010, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.notes;

import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;

import java.io.IOException;

import static org.eclipse.jgit.lib.Constants.OBJECT_ID_STRING_LENGTH;
import static org.eclipse.jgit.lib.Constants.encodeASCII;
import static org.eclipse.jgit.lib.FileMode.TREE;
import static org.eclipse.jgit.util.RawParseUtils.parseHexInt4;

final class NoteParser extends CanonicalTreeParser {

	static InMemoryNoteBucket parse(AbbreviatedObjectId prefix,
									final ObjectId treeId, final ObjectReader reader)
			throws IOException {
		return new NoteParser(prefix, reader, treeId).parse();
	}

	private final int prefixLen;

	private final int pathPadding;

	private NonNoteEntry firstNonNote;

	private NonNoteEntry lastNonNote;

	private NoteParser(AbbreviatedObjectId prefix, ObjectReader r, ObjectId t)
			throws IOException {
		super(encodeASCII(prefix.name()), r, t);
		prefixLen = prefix.length();

		pathPadding = 0 < prefixLen ? 1 : 0;
		if(0 < pathPadding)
			System.arraycopy(path, 0, path, pathPadding, prefixLen);
	}

	private InMemoryNoteBucket parse() {
		InMemoryNoteBucket r = parseTree();
		r.nonNotes = firstNonNote;
		return r;
	}

	private InMemoryNoteBucket parseTree() {
		for(; !eof(); next(1)) {
			if(pathLen == pathPadding + OBJECT_ID_STRING_LENGTH && isHex())
				return parseLeafTree();

			else if(getNameLength() == 2 && isHex() && isTree())
				return parseFanoutTree();

			else
				storeNonNote();
		}

		return new LeafBucket(prefixLen);
	}

	private LeafBucket parseLeafTree() {
		final LeafBucket leaf = new LeafBucket(prefixLen);
		final MutableObjectId idBuf = new MutableObjectId();

		for(; !eof(); next(1)) {
			if(parseObjectId(idBuf))
				leaf.parseOneEntry(idBuf, getEntryObjectId());
			else
				storeNonNote();
		}

		return leaf;
	}

	private boolean parseObjectId(MutableObjectId id) {
		if(pathLen == pathPadding + OBJECT_ID_STRING_LENGTH) {
			try {
				id.fromString(path, pathPadding);
				return true;
			} catch(ArrayIndexOutOfBoundsException notHex) {
				return false;
			}
		}
		return false;
	}

	private FanoutBucket parseFanoutTree() {
		final FanoutBucket fanout = new FanoutBucket(prefixLen);

		for(; !eof(); next(1)) {
			final int cell = parseFanoutCell();
			if(0 <= cell)
				fanout.setBucket(cell, getEntryObjectId());
			else
				storeNonNote();
		}

		return fanout;
	}

	private int parseFanoutCell() {
		if(getNameLength() == 2 && isTree()) {
			try {
				return (parseHexInt4(path[pathOffset]) << 4)
						| parseHexInt4(path[pathOffset + 1]);
			} catch(ArrayIndexOutOfBoundsException notHex) {
				return -1;
			}
		}
		return -1;
	}

	private void storeNonNote() {
		ObjectId id = getEntryObjectId();
		FileMode fileMode = getEntryFileMode();

		byte[] name = new byte[getNameLength()];
		getName(name, 0);

		NonNoteEntry ent = new NonNoteEntry(name, fileMode, id);
		if(firstNonNote == null)
			firstNonNote = ent;
		if(lastNonNote != null)
			lastNonNote.next = ent;
		lastNonNote = ent;
	}

	private boolean isTree() {
		return TREE.equals(mode);
	}

	private boolean isHex() {
		try {
			for(int i = pathOffset; i < pathLen; i++)
				parseHexInt4(path[i]);
			return true;
		} catch(ArrayIndexOutOfBoundsException fail) {
			return false;
		}
	}
}
