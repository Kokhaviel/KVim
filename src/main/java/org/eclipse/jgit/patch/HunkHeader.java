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

import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.util.MutableInteger;

import java.text.MessageFormat;

import static org.eclipse.jgit.util.RawParseUtils.*;

public class HunkHeader {
	public abstract static class OldImage {
		int startLine;
		int lineCount;
		int nDeleted;
		int nAdded;

		public int getStartLine() {
			return startLine;
		}

		public int getLineCount() {
			return lineCount;
		}

		public int getLinesDeleted() {
			return nDeleted;
		}

		public abstract AbbreviatedObjectId getId();
	}

	final FileHeader file;
	final int startOffset;
	int endOffset;
	private final OldImage old;
	int newStartLine;
	int newLineCount;
	int nContext;

	private EditList editList;

	HunkHeader(FileHeader fh, int offset) {
		this(fh, offset, new OldImage() {
			@Override
			public AbbreviatedObjectId getId() {
				return fh.getOldId();
			}
		});
	}

	HunkHeader(FileHeader fh, int offset, OldImage oi) {
		file = fh;
		startOffset = offset;
		old = oi;
	}

	HunkHeader(FileHeader fh, EditList editList) {
		this(fh, fh.buf.length);
		this.editList = editList;
		endOffset = startOffset;
		nContext = 0;
		if(editList.isEmpty()) {
			newStartLine = 0;
			newLineCount = 0;
		} else {
			newStartLine = editList.get(0).getBeginB();
			Edit last = editList.get(editList.size() - 1);
			newLineCount = last.getEndB() - newStartLine;
		}
	}

	public FileHeader getFileHeader() {
		return file;
	}

	public byte[] getBuffer() {
		return file.buf;
	}

	public int getStartOffset() {
		return startOffset;
	}

	public int getEndOffset() {
		return endOffset;
	}

	public OldImage getOldImage() {
		return old;
	}

	public int getNewStartLine() {
		return newStartLine;
	}

	public int getNewLineCount() {
		return newLineCount;
	}

	public int getLinesContext() {
		return nContext;
	}

	public EditList toEditList() {
		if(editList == null) {
			editList = new EditList();
			final byte[] buf = file.buf;
			int c = nextLF(buf, startOffset);
			int oLine = old.startLine;
			int nLine = newStartLine;
			Edit in = null;

			SCAN:
			for(; c < endOffset; c = nextLF(buf, c)) {
				switch(buf[c]) {
					case ' ':
					case '\n':
						in = null;
						oLine++;
						nLine++;
						continue;

					case '-':
						if(in == null) {
							in = new Edit(oLine - 1, nLine - 1);
							editList.add(in);
						}
						oLine++;
						in.extendA();
						continue;

					case '+':
						if(in == null) {
							in = new Edit(oLine - 1, nLine - 1);
							editList.add(in);
						}
						nLine++;
						in.extendB();
						continue;

					case '\\':
						continue;

					default:
						break SCAN;
				}
			}
		}
		return editList;
	}

	void parseHeader() {
		final byte[] buf = file.buf;
		final MutableInteger ptr = new MutableInteger();
		ptr.value = nextLF(buf, startOffset, ' ');
		old.startLine = -parseBase10(buf, ptr.value, ptr);
		if(buf[ptr.value] == ',')
			old.lineCount = parseBase10(buf, ptr.value + 1, ptr);
		else
			old.lineCount = 1;

		newStartLine = parseBase10(buf, ptr.value + 1, ptr);
		if(buf[ptr.value] == ',')
			newLineCount = parseBase10(buf, ptr.value + 1, ptr);
		else
			newLineCount = 1;
	}

	int parseBody(Patch script, int end) {
		final byte[] buf = file.buf;
		int c = nextLF(buf, startOffset), last = c;

		old.nDeleted = 0;
		old.nAdded = 0;

		SCAN:
		for(; c < end; last = c, c = nextLF(buf, c)) {
			switch(buf[c]) {
				case ' ':
				case '\n':
					nContext++;
					continue;

				case '-':
					old.nDeleted++;
					continue;

				case '+':
					old.nAdded++;
					continue;

				case '\\':
					continue;

				default:
					break SCAN;
			}
		}

		if(last < end && nContext + old.nDeleted - 1 == old.lineCount
				&& nContext + old.nAdded == newLineCount
				&& match(buf, last, Patch.SIG_FOOTER) >= 0) {
			old.nDeleted--;
			return last;
		}

		if(nContext + old.nDeleted < old.lineCount) {
			final int missingCount = old.lineCount - (nContext + old.nDeleted);
			script.error(buf, startOffset, MessageFormat.format(
					JGitText.get().truncatedHunkOldLinesMissing,
					missingCount));

		} else if(nContext + old.nAdded < newLineCount) {
			final int missingCount = newLineCount - (nContext + old.nAdded);
			script.error(buf, startOffset, MessageFormat.format(
					JGitText.get().truncatedHunkNewLinesMissing,
					missingCount));

		} else if(nContext + old.nDeleted > old.lineCount
				|| nContext + old.nAdded > newLineCount) {
			final String oldcnt = old.lineCount + ":" + newLineCount;
			final String newcnt = (nContext + old.nDeleted) + ":"
					+ (nContext + old.nAdded);
			script.warn(buf, startOffset, MessageFormat.format(
					JGitText.get().hunkHeaderDoesNotMatchBodyLineCountOf, oldcnt, newcnt));
		}

		return c;
	}

	@Override
	public String toString() {
		return "HunkHeader[" +
				getOldImage().getStartLine() +
				',' +
				getOldImage().getLineCount() +
				"->" +
				getNewStartLine() + ',' + getNewLineCount() +
				']';
	}
}
