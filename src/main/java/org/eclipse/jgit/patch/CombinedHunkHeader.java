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

import static org.eclipse.jgit.util.RawParseUtils.nextLF;
import static org.eclipse.jgit.util.RawParseUtils.parseBase10;

import java.text.MessageFormat;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.util.MutableInteger;

public class CombinedHunkHeader extends HunkHeader {
	private abstract static class CombinedOldImage extends OldImage {
		int nContext;
	}

	private final CombinedOldImage[] old;

	CombinedHunkHeader(CombinedFileHeader fh, int offset) {
		super(fh, offset, null);
		old = new CombinedOldImage[fh.getParentCount()];
		for(int i = 0; i < old.length; i++) {
			final int imagePos = i;
			old[i] = new CombinedOldImage() {
				@Override
				public AbbreviatedObjectId getId() {
					return fh.getOldId(imagePos);
				}
			};
		}
	}

	@Override
	public CombinedFileHeader getFileHeader() {
		return (CombinedFileHeader) super.getFileHeader();
	}

	@Override
	public OldImage getOldImage() {
		return getOldImage(0);
	}

	public OldImage getOldImage(int nthParent) {
		return old[nthParent];
	}

	@Override
	void parseHeader() {
		final byte[] buf = file.buf;
		final MutableInteger ptr = new MutableInteger();
		ptr.value = nextLF(buf, startOffset, ' ');

		for(CombinedOldImage o : old) {
			o.startLine = -parseBase10(buf, ptr.value, ptr);
			if(buf[ptr.value] == ',') {
				o.lineCount = parseBase10(buf, ptr.value + 1, ptr);
			} else {
				o.lineCount = 1;
			}
		}

		newStartLine = parseBase10(buf, ptr.value + 1, ptr);
		if(buf[ptr.value] == ',')
			newLineCount = parseBase10(buf, ptr.value + 1, ptr);
		else
			newLineCount = 1;
	}

	@Override
	int parseBody(Patch script, int end) {
		final byte[] buf = file.buf;
		int c = nextLF(buf, startOffset);

		for(CombinedOldImage o : old) {
			o.nDeleted = 0;
			o.nAdded = 0;
			o.nContext = 0;
		}
		nContext = 0;
		int nAdded = 0;

		SCAN:
		for(int eol; c < end; c = eol) {
			eol = nextLF(buf, c);

			if(eol - c < old.length + 1) {
				break;
			}

			switch(buf[c]) {
				case ' ':
				case '-':
				case '+':
					break;

				default:
					break SCAN;
			}

			int localcontext = 0;
			for(int ancestor = 0; ancestor < old.length; ancestor++) {
				switch(buf[c + ancestor]) {
					case ' ':
						localcontext++;
						old[ancestor].nContext++;
						continue;

					case '-':
						old[ancestor].nDeleted++;
						continue;

					case '+':
						old[ancestor].nAdded++;
						nAdded++;
						continue;

					default:
						break SCAN;
				}
			}
			if(localcontext == old.length)
				nContext++;
		}

		for(int ancestor = 0; ancestor < old.length; ancestor++) {
			final CombinedOldImage o = old[ancestor];
			final int cmp = o.nContext + o.nDeleted;
			if(cmp < o.lineCount) {
				final int missingCnt = o.lineCount - cmp;
				script.error(buf, startOffset, MessageFormat.format(
						JGitText.get().truncatedHunkLinesMissingForAncestor,
						missingCnt,
						ancestor + 1));
			}
		}

		if(nContext + nAdded < newLineCount) {
			final int missingCount = newLineCount - (nContext + nAdded);
			script.error(buf, startOffset, MessageFormat.format(
					JGitText.get().truncatedHunkNewLinesMissing,
					missingCount));
		}

		return c;
	}

}
