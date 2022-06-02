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

import static org.eclipse.jgit.lib.Constants.encodeASCII;
import static org.eclipse.jgit.patch.FileHeader.NEW_NAME;
import static org.eclipse.jgit.patch.FileHeader.OLD_NAME;
import static org.eclipse.jgit.patch.FileHeader.isHunkHdr;
import static org.eclipse.jgit.util.RawParseUtils.match;
import static org.eclipse.jgit.util.RawParseUtils.nextLF;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.util.TemporaryBuffer;

public class Patch {

	static final byte[] DIFF_GIT = encodeASCII("diff --git ");
	private static final byte[] DIFF_CC = encodeASCII("diff --cc ");
	private static final byte[] DIFF_COMBINED = encodeASCII("diff --combined ");
	private static final byte[][] BIN_HEADERS = new byte[][] {
			encodeASCII("Binary files "), encodeASCII("Files "),};
	private static final byte[] BIN_TRAILER = encodeASCII(" differ\n");
	private static final byte[] GIT_BINARY = encodeASCII("GIT binary patch\n");
	static final byte[] SIG_FOOTER = encodeASCII("-- \n");

	private final List<FileHeader> files;
	private final List<FormatError> errors;

	public Patch() {
		files = new ArrayList<>();
		errors = new ArrayList<>(0);
	}

	public void addFile(FileHeader fh) {
		files.add(fh);
	}

	public List<? extends FileHeader> getFiles() {
		return files;
	}

	public void addError(FormatError err) {
		errors.add(err);
	}

	public List<FormatError> getErrors() {
		return errors;
	}

	public void parse(InputStream is) throws IOException {
		final byte[] buf = readFully(is);
		parse(buf, 0, buf.length);
	}

	private static byte[] readFully(InputStream is) throws IOException {
		try(TemporaryBuffer b = new TemporaryBuffer.Heap(Integer.MAX_VALUE)) {
			b.copy(is);
			return b.toByteArray();
		}
	}

	public void parse(byte[] buf, int ptr, int end) {
		while(ptr < end)
			ptr = parseFile(buf, ptr, end);
	}

	private int parseFile(byte[] buf, int c, int end) {
		while(c < end) {
			if(isHunkHdr(buf, c, end) >= 1) {
				error(buf, c, JGitText.get().hunkDisconnectedFromFile);
				c = nextLF(buf, c);
				continue;
			}

			if(match(buf, c, DIFF_GIT) >= 0)
				return parseDiffGit(buf, c, end);
			if(match(buf, c, DIFF_CC) >= 0)
				return parseDiffCombined(DIFF_CC, buf, c, end);
			if(match(buf, c, DIFF_COMBINED) >= 0)
				return parseDiffCombined(DIFF_COMBINED, buf, c, end);

			final int n = nextLF(buf, c);
			if(n >= end) {
				return end;
			}

			if(n - c < 6) {
				c = n;
				continue;
			}

			if(match(buf, c, OLD_NAME) >= 0 && match(buf, n, NEW_NAME) >= 0) {
				final int f = nextLF(buf, n);
				if(f >= end)
					return end;
				if(isHunkHdr(buf, f, end) == 1)
					return parseTraditionalPatch(buf, c, end);
			}

			c = n;
		}
		return c;
	}

	private int parseDiffGit(byte[] buf, int start, int end) {
		final FileHeader fh = new FileHeader(buf, start);
		int ptr = fh.parseGitFileName(start + DIFF_GIT.length, end);
		if(ptr < 0)
			return skipFile(buf, start);

		ptr = fh.parseGitHeaders(ptr, end);
		ptr = parseHunks(fh, ptr, end);
		fh.endOffset = ptr;
		addFile(fh);
		return ptr;
	}

	private int parseDiffCombined(final byte[] hdr, final byte[] buf,
								  final int start, final int end) {
		final CombinedFileHeader fh = new CombinedFileHeader(buf, start);
		int ptr = fh.parseGitFileName(start + hdr.length, end);
		if(ptr < 0)
			return skipFile(buf, start);

		ptr = fh.parseGitHeaders(ptr, end);
		ptr = parseHunks(fh, ptr, end);
		fh.endOffset = ptr;
		addFile(fh);
		return ptr;
	}

	private int parseTraditionalPatch(final byte[] buf, final int start,
									  final int end) {
		final FileHeader fh = new FileHeader(buf, start);
		int ptr = fh.parseTraditionalHeaders(start, end);
		ptr = parseHunks(fh, ptr, end);
		fh.endOffset = ptr;
		addFile(fh);
		return ptr;
	}

	private static int skipFile(byte[] buf, int ptr) {
		ptr = nextLF(buf, ptr);
		if(match(buf, ptr, OLD_NAME) >= 0)
			ptr = nextLF(buf, ptr);
		return ptr;
	}

	private int parseHunks(FileHeader fh, int c, int end) {
		final byte[] buf = fh.buf;
		while(c < end) {
			if(match(buf, c, DIFF_GIT) >= 0)
				break;
			if(match(buf, c, DIFF_CC) >= 0)
				break;
			if(match(buf, c, DIFF_COMBINED) >= 0)
				break;
			if(match(buf, c, OLD_NAME) >= 0)
				break;
			if(match(buf, c, NEW_NAME) >= 0)
				break;

			if(isHunkHdr(buf, c, end) == fh.getParentCount()) {
				final HunkHeader h = fh.newHunkHeader(c);
				h.parseHeader();
				c = h.parseBody(this, end);
				h.endOffset = c;
				fh.addHunk(h);
				if(c < end) {
					switch(buf[c]) {
						case '@':
						case 'd':
						case '\n':
							break;
						default:
							if(match(buf, c, SIG_FOOTER) < 0)
								warn(buf, c, JGitText.get().unexpectedHunkTrailer);
					}
				}
				continue;
			}

			final int eol = nextLF(buf, c);
			if(fh.getHunks().isEmpty() && match(buf, c, GIT_BINARY) >= 0) {
				fh.patchType = FileHeader.PatchType.GIT_BINARY;
				return parseGitBinary(fh, eol, end);
			}

			if(fh.getHunks().isEmpty() && BIN_TRAILER.length < eol - c
					&& match(buf, eol - BIN_TRAILER.length, BIN_TRAILER) >= 0
					&& matchAny(buf, c)) {
				fh.patchType = FileHeader.PatchType.BINARY;
				return eol;
			}

			c = eol;
		}

		if(fh.getHunks().isEmpty()
				&& fh.getPatchType() == FileHeader.PatchType.UNIFIED
				&& !fh.hasMetaDataChanges()) {
			fh.patchType = FileHeader.PatchType.BINARY;
		}

		return c;
	}

	private int parseGitBinary(FileHeader fh, int c, int end) {
		final BinaryHunk postImage = new BinaryHunk(fh, c);
		final int nEnd = postImage.parseHunk(c, end);
		if(nEnd < 0) {
			error(fh.buf, c, JGitText.get().missingForwardImageInGITBinaryPatch);
			return c;
		}
		c = nEnd;
		postImage.endOffset = c;
		fh.forwardBinaryHunk = postImage;

		final BinaryHunk preImage = new BinaryHunk(fh, c);
		final int oEnd = preImage.parseHunk(c, end);
		if(oEnd >= 0) {
			c = oEnd;
			preImage.endOffset = c;
			fh.reverseBinaryHunk = preImage;
		}

		return c;
	}

	void warn(byte[] buf, int ptr, String msg) {
		addError(new FormatError(buf, ptr, FormatError.Severity.WARNING, msg));
	}

	void error(byte[] buf, int ptr, String msg) {
		addError(new FormatError(buf, ptr, FormatError.Severity.ERROR, msg));
	}

	private static boolean matchAny(final byte[] buf, final int c) {
		for(byte[] s : Patch.BIN_HEADERS) {
			if(match(buf, c, s) >= 0)
				return true;
		}
		return false;
	}
}
