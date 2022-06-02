/*
 * Copyright (C) 2008, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.patch;

import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.lib.FileMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.eclipse.jgit.lib.Constants.encodeASCII;
import static org.eclipse.jgit.util.RawParseUtils.match;
import static org.eclipse.jgit.util.RawParseUtils.nextLF;

public class CombinedFileHeader extends FileHeader {
	private static final byte[] MODE = encodeASCII("mode ");

	private AbbreviatedObjectId[] oldIds;

	private FileMode[] oldModes;

	CombinedFileHeader(byte[] b, int offset) {
		super(b, offset);
	}

	@Override
	@SuppressWarnings("unchecked")
	public List<? extends CombinedHunkHeader> getHunks() {
		return (List<CombinedHunkHeader>) super.getHunks();
	}

	@Override
	public int getParentCount() {
		return oldIds.length;
	}

	@Override
	public FileMode getOldMode() {
		return getOldMode(0);
	}

	public FileMode getOldMode(int nthParent) {
		return oldModes[nthParent];
	}

	@Override
	public AbbreviatedObjectId getOldId() {
		return getOldId(0);
	}

	public AbbreviatedObjectId getOldId(int nthParent) {
		return oldIds[nthParent];
	}

	@Override
	int parseGitHeaders(int ptr, int end) {
		while(ptr < end) {
			final int eol = nextLF(buf, ptr);
			if(isHunkHdr(buf, ptr, end) >= 1) {
				break;

			} else if(match(buf, ptr, OLD_NAME) >= 0) {
				parseOldName(ptr, eol);

			} else if(match(buf, ptr, NEW_NAME) >= 0) {
				parseNewName(ptr, eol);

			} else if(match(buf, ptr, INDEX) >= 0) {
				parseIndexLine(ptr + INDEX.length, eol);

			} else if(match(buf, ptr, MODE) >= 0) {
				parseModeLine(ptr + MODE.length, eol);

			} else if(match(buf, ptr, NEW_FILE_MODE) >= 0) {
				parseNewFileMode(ptr, eol);

			} else if(match(buf, ptr, DELETED_FILE_MODE) >= 0) {
				parseDeletedFileMode(ptr + DELETED_FILE_MODE.length, eol);

			} else {
				break;
			}

			ptr = eol;
		}
		return ptr;
	}

	@Override
	protected void parseIndexLine(int ptr, int eol) {
		final List<AbbreviatedObjectId> ids = new ArrayList<>();
		while(ptr < eol) {
			final int comma = nextLF(buf, ptr, ',');
			if(eol <= comma)
				break;
			ids.add(AbbreviatedObjectId.fromString(buf, ptr, comma - 1));
			ptr = comma;
		}

		oldIds = new AbbreviatedObjectId[ids.size() + 1];
		ids.toArray(oldIds);
		final int dot2 = nextLF(buf, ptr, '.');
		oldIds[ids.size()] = AbbreviatedObjectId.fromString(buf, ptr, dot2 - 1);
		newId = AbbreviatedObjectId.fromString(buf, dot2 + 1, eol - 1);
		oldModes = new FileMode[oldIds.length];
	}

	@Override
	protected void parseNewFileMode(int ptr, int eol) {
		Arrays.fill(oldModes, FileMode.MISSING);
		super.parseNewFileMode(ptr, eol);
	}

	@Override
	HunkHeader newHunkHeader(int offset) {
		return new CombinedHunkHeader(this, offset);
	}

	private void parseModeLine(int ptr, int eol) {
		int n = 0;
		while(ptr < eol) {
			final int comma = nextLF(buf, ptr, ',');
			if(eol <= comma)
				break;
			oldModes[n++] = parseFileMode(ptr, comma);
			ptr = comma;
		}
		final int dot2 = nextLF(buf, ptr, '.');
		oldModes[n] = parseFileMode(ptr, dot2);
		newMode = parseFileMode(dot2 + 1, eol);
	}

	private void parseDeletedFileMode(int ptr, int eol) {
		changeType = ChangeType.DELETE;
		int n = 0;
		while(ptr < eol) {
			final int comma = nextLF(buf, ptr, ',');
			if(eol <= comma)
				break;
			oldModes[n++] = parseFileMode(ptr, comma);
			ptr = comma;
		}
		oldModes[n] = parseFileMode(ptr, eol);
		newMode = FileMode.MISSING;
	}
}
