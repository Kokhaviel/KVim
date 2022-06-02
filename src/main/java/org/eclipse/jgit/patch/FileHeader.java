/*
 * Copyright (C) 2008-2009, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.patch;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.jgit.lib.Constants.encodeASCII;
import static org.eclipse.jgit.util.RawParseUtils.decode;
import static org.eclipse.jgit.util.RawParseUtils.match;
import static org.eclipse.jgit.util.RawParseUtils.nextLF;
import static org.eclipse.jgit.util.RawParseUtils.parseBase10;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.util.QuotedString;

public class FileHeader extends DiffEntry {

	private static final byte[] OLD_MODE = encodeASCII("old mode ");
	private static final byte[] NEW_MODE = encodeASCII("new mode ");
	static final byte[] DELETED_FILE_MODE = encodeASCII("deleted file mode ");
	static final byte[] NEW_FILE_MODE = encodeASCII("new file mode ");
	private static final byte[] COPY_FROM = encodeASCII("copy from ");
	private static final byte[] COPY_TO = encodeASCII("copy to ");
	private static final byte[] RENAME_OLD = encodeASCII("rename old ");
	private static final byte[] RENAME_NEW = encodeASCII("rename new ");
	private static final byte[] RENAME_FROM = encodeASCII("rename from ");
	private static final byte[] RENAME_TO = encodeASCII("rename to ");
	private static final byte[] SIMILARITY_INDEX = encodeASCII("similarity index ");
	private static final byte[] DISSIMILARITY_INDEX = encodeASCII("dissimilarity index ");
	static final byte[] INDEX = encodeASCII("index ");
	static final byte[] OLD_NAME = encodeASCII("--- ");
	static final byte[] NEW_NAME = encodeASCII("+++ ");

	public enum PatchType {
		UNIFIED,
		BINARY,
		GIT_BINARY
	}

	final byte[] buf;
	final int startOffset;
	int endOffset;
	PatchType patchType;
	private List<HunkHeader> hunks;
	BinaryHunk forwardBinaryHunk;
	BinaryHunk reverseBinaryHunk;

	public FileHeader(byte[] headerLines, EditList edits, PatchType type) {
		this(headerLines, 0);
		endOffset = headerLines.length;
		int ptr = parseGitFileName(Patch.DIFF_GIT.length, headerLines.length);
		parseGitHeaders(ptr, headerLines.length);
		this.patchType = type;
		addHunk(new HunkHeader(this, edits));
	}

	FileHeader(byte[] b, int offset) {
		buf = b;
		startOffset = offset;
		changeType = ChangeType.MODIFY;
		patchType = PatchType.UNIFIED;
	}

	int getParentCount() {
		return 1;
	}

	public byte[] getBuffer() {
		return buf;
	}

	public int getStartOffset() {
		return startOffset;
	}

	public int getEndOffset() {
		return endOffset;
	}

	public PatchType getPatchType() {
		return patchType;
	}

	public boolean hasMetaDataChanges() {
		return changeType != ChangeType.MODIFY || newMode != oldMode;
	}

	public List<? extends HunkHeader> getHunks() {
		if(hunks == null)
			return Collections.emptyList();
		return hunks;
	}

	void addHunk(HunkHeader h) {
		if(h.getFileHeader() != this)
			throw new IllegalArgumentException(JGitText.get().hunkBelongsToAnotherFile);
		if(hunks == null)
			hunks = new ArrayList<>();
		hunks.add(h);
	}

	HunkHeader newHunkHeader(int offset) {
		return new HunkHeader(this, offset);
	}

	public BinaryHunk getForwardBinaryHunk() {
		return forwardBinaryHunk;
	}

	public EditList toEditList() {
		final EditList r = new EditList();
		for(HunkHeader hunk : hunks)
			r.addAll(hunk.toEditList());
		return r;
	}

	int parseGitFileName(int ptr, int end) {
		final int eol = nextLF(buf, ptr);
		final int bol = ptr;
		if(eol >= end) {
			return -1;
		}

		final int aStart = nextLF(buf, ptr, '/');
		if(aStart >= eol)
			return eol;

		while(ptr < eol) {
			final int sp = nextLF(buf, ptr, ' ');
			if(sp >= eol) {
				return eol;
			}
			final int bStart = nextLF(buf, sp, '/');
			if(bStart >= eol)
				return eol;

			if(eq(aStart, sp - 1, bStart, eol - 1)) {
				if(buf[bol] == '"') {
					if(buf[sp - 2] != '"') {
						return eol;
					}
					oldPath = QuotedString.GIT_PATH.dequote(buf, bol, sp - 1);
					oldPath = p1(oldPath);
				} else {
					oldPath = decode(UTF_8, buf, aStart, sp - 1);
				}
				newPath = oldPath;
				return eol;
			}

			ptr = sp;
		}

		return eol;
	}

	int parseGitHeaders(int ptr, int end) {
		while(ptr < end) {
			final int eol = nextLF(buf, ptr);
			if(isHunkHdr(buf, ptr, eol) >= 1) {
				break;

			} else if(match(buf, ptr, OLD_NAME) >= 0) {
				parseOldName(ptr, eol);

			} else if(match(buf, ptr, NEW_NAME) >= 0) {
				parseNewName(ptr, eol);

			} else if(match(buf, ptr, OLD_MODE) >= 0) {
				oldMode = parseFileMode(ptr + OLD_MODE.length, eol);

			} else if(match(buf, ptr, NEW_MODE) >= 0) {
				newMode = parseFileMode(ptr + NEW_MODE.length, eol);

			} else if(match(buf, ptr, DELETED_FILE_MODE) >= 0) {
				oldMode = parseFileMode(ptr + DELETED_FILE_MODE.length, eol);
				newMode = FileMode.MISSING;
				changeType = ChangeType.DELETE;

			} else if(match(buf, ptr, NEW_FILE_MODE) >= 0) {
				parseNewFileMode(ptr, eol);

			} else if(match(buf, ptr, COPY_FROM) >= 0) {
				oldPath = parseName(oldPath, ptr + COPY_FROM.length, eol);
				changeType = ChangeType.COPY;

			} else if(match(buf, ptr, COPY_TO) >= 0) {
				newPath = parseName(newPath, ptr + COPY_TO.length, eol);
				changeType = ChangeType.COPY;

			} else if(match(buf, ptr, RENAME_OLD) >= 0) {
				oldPath = parseName(oldPath, ptr + RENAME_OLD.length, eol);
				changeType = ChangeType.RENAME;

			} else if(match(buf, ptr, RENAME_NEW) >= 0) {
				newPath = parseName(newPath, ptr + RENAME_NEW.length, eol);
				changeType = ChangeType.RENAME;

			} else if(match(buf, ptr, RENAME_FROM) >= 0) {
				oldPath = parseName(oldPath, ptr + RENAME_FROM.length, eol);
				changeType = ChangeType.RENAME;

			} else if(match(buf, ptr, RENAME_TO) >= 0) {
				newPath = parseName(newPath, ptr + RENAME_TO.length, eol);
				changeType = ChangeType.RENAME;

			} else if(match(buf, ptr, SIMILARITY_INDEX) >= 0) {
				score = parseBase10(buf, ptr + SIMILARITY_INDEX.length, null);

			} else if(match(buf, ptr, DISSIMILARITY_INDEX) >= 0) {
				score = parseBase10(buf, ptr + DISSIMILARITY_INDEX.length, null);

			} else if(match(buf, ptr, INDEX) >= 0) {
				parseIndexLine(ptr + INDEX.length, eol);

			} else {
				break;
			}

			ptr = eol;
		}
		return ptr;
	}

	void parseOldName(int ptr, int eol) {
		oldPath = p1(parseName(oldPath, ptr + OLD_NAME.length, eol));
		if(oldPath.equals(DEV_NULL))
			changeType = ChangeType.ADD;
	}

	void parseNewName(int ptr, int eol) {
		newPath = p1(parseName(newPath, ptr + NEW_NAME.length, eol));
		if(newPath.equals(DEV_NULL))
			changeType = ChangeType.DELETE;
	}

	void parseNewFileMode(int ptr, int eol) {
		oldMode = FileMode.MISSING;
		newMode = parseFileMode(ptr + NEW_FILE_MODE.length, eol);
		changeType = ChangeType.ADD;
	}

	int parseTraditionalHeaders(int ptr, int end) {
		while(ptr < end) {
			final int eol = nextLF(buf, ptr);
			if(isHunkHdr(buf, ptr, eol) >= 1) {
				break;

			} else if(match(buf, ptr, OLD_NAME) >= 0) {
				parseOldName(ptr, eol);

			} else if(match(buf, ptr, NEW_NAME) >= 0) {
				parseNewName(ptr, eol);

			} else {
				break;
			}

			ptr = eol;
		}
		return ptr;
	}

	private String parseName(String expect, int ptr, int end) {
		if(ptr == end)
			return expect;

		String r;
		if(buf[ptr] == '"') {
			r = QuotedString.GIT_PATH.dequote(buf, ptr, end - 1);
		} else {
			int tab = end;
			while(ptr < tab && buf[tab - 1] != '\t')
				tab--;
			if(ptr == tab)
				tab = end;
			r = decode(UTF_8, buf, ptr, tab - 1);
		}

		if(r.equals(DEV_NULL))
			r = DEV_NULL;
		return r;
	}

	private static String p1(final String r) {
		final int s = r.indexOf('/');
		return s > 0 ? r.substring(s + 1) : r;
	}

	FileMode parseFileMode(int ptr, int end) {
		int tmp = 0;
		while(ptr < end - 1) {
			tmp <<= 3;
			tmp += buf[ptr++] - '0';
		}
		return FileMode.fromBits(tmp);
	}

	void parseIndexLine(int ptr, int end) {
		final int dot2 = nextLF(buf, ptr, '.');
		final int mode = nextLF(buf, dot2, ' ');

		oldId = AbbreviatedObjectId.fromString(buf, ptr, dot2 - 1);
		newId = AbbreviatedObjectId.fromString(buf, dot2 + 1, mode - 1);

		if(mode < end)
			newMode = oldMode = parseFileMode(mode, end);
	}

	private boolean eq(int aPtr, int aEnd, int bPtr, int bEnd) {
		if(aEnd - aPtr != bEnd - bPtr) {
			return false;
		}
		while(aPtr < aEnd) {
			if(buf[aPtr++] != buf[bPtr++])
				return false;
		}
		return true;
	}

	static int isHunkHdr(byte[] buf, int start, int end) {
		int ptr = start;
		while(ptr < end && buf[ptr] == '@')
			ptr++;
		if(ptr - start < 2)
			return 0;
		if(ptr == end || buf[ptr++] != ' ')
			return 0;
		if(ptr == end || buf[ptr++] != '-')
			return 0;
		return (ptr - 3) - start;
	}
}
