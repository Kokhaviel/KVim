/*
 * Copyright (C) 2013, Christian Halstrick <christian.halstrick@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.lib;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedList;
import java.util.List;

import org.eclipse.jgit.lib.RebaseTodoLine.Action;
import org.eclipse.jgit.util.IO;
import org.eclipse.jgit.util.RawParseUtils;

public class RebaseTodoFile {
	private final Repository repo;

	public RebaseTodoFile(Repository repo) {
		this.repo = repo;
	}

	public List<RebaseTodoLine> readRebaseTodo(String path,
			boolean includeComments) throws IOException {
		byte[] buf = IO.readFully(new File(repo.getDirectory(), path));
		int ptr = 0;
		int tokenBegin;
		List<RebaseTodoLine> r = new LinkedList<>();
		while (ptr < buf.length) {
			tokenBegin = ptr;
			ptr = RawParseUtils.nextLF(buf, ptr);
			int lineStart = tokenBegin;
			int lineEnd = ptr - 2;
			if (lineEnd >= 0 && buf[lineEnd] == '\r')
				lineEnd--;
			if (buf[tokenBegin] == '#') {
				if (includeComments)
					parseComments(buf, tokenBegin, r, lineEnd);
			} else {
				tokenBegin = nextParsableToken(buf, tokenBegin, lineEnd);
				if (tokenBegin == -1) {
					if (includeComments)
						r.add(new RebaseTodoLine(RawParseUtils.decode(buf,
								lineStart, 1 + lineEnd)));
					continue;
				}
				RebaseTodoLine line = parseLine(buf, tokenBegin, lineEnd);
				if (line == null)
					continue;
				r.add(line);
			}
		}
		return r;
	}

	private static void parseComments(byte[] buf, int tokenBegin,
			List<RebaseTodoLine> r, int lineEnd) {
		RebaseTodoLine line = null;
		String commentString = RawParseUtils.decode(buf,
				tokenBegin, lineEnd + 1);
		try {
			int skip = tokenBegin + 1;
			skip = nextParsableToken(buf, skip, lineEnd);
			if (skip != -1) {
				line = parseLine(buf, skip, lineEnd);
				if (line != null) {
					line.setAction(Action.COMMENT);
					line.setComment(commentString);
				}
			}
		} catch (Exception e) {
			line = null;
		} finally {
			if (line == null)
				line = new RebaseTodoLine(commentString);
			r.add(line);
		}
	}

	private static int nextParsableToken(byte[] buf, int tokenBegin, int lineEnd) {
		while (tokenBegin <= lineEnd
				&& (buf[tokenBegin] == ' ' || buf[tokenBegin] == '\t' || buf[tokenBegin] == '\r'))
			tokenBegin++;
		if (tokenBegin > lineEnd)
			return -1;
		return tokenBegin;
	}

	private static RebaseTodoLine parseLine(byte[] buf, int tokenBegin,
			int lineEnd) {
		RebaseTodoLine.Action action = null;
		AbbreviatedObjectId commit = null;

		int nextSpace = RawParseUtils.next(buf, tokenBegin, ' ');
		int tokenCount = 0;
		while (tokenCount < 3 && nextSpace <= lineEnd) {
			switch (tokenCount) {
			case 0:
				String actionToken = new String(buf, tokenBegin,
						nextSpace - tokenBegin - 1, UTF_8);
				tokenBegin = nextSpace;
				action = RebaseTodoLine.Action.parse(actionToken);
				break;
			case 1:
				nextSpace = RawParseUtils.next(buf, tokenBegin, ' ');
				String commitToken;
				if (nextSpace > lineEnd + 1) {
					commitToken = new String(buf, tokenBegin,
							lineEnd - tokenBegin + 1, UTF_8);
				} else {
					commitToken = new String(buf, tokenBegin,
							nextSpace - tokenBegin - 1, UTF_8);
				}
				tokenBegin = nextSpace;
				commit = AbbreviatedObjectId.fromString(commitToken);
				break;
			case 2:
				return new RebaseTodoLine(action, commit,
						RawParseUtils.decode(buf, tokenBegin, 1 + lineEnd));
			}
			tokenCount++;
		}
		if (tokenCount == 2)
			return new RebaseTodoLine(action, commit, "");
		return null;
	}

	public void writeRebaseTodoFile(String path, List<RebaseTodoLine> steps,
			boolean append) throws IOException {
		try (OutputStream fw = new BufferedOutputStream(new FileOutputStream(
				new File(repo.getDirectory(), path), append))) {
			StringBuilder sb = new StringBuilder();
			for (RebaseTodoLine step : steps) {
				sb.setLength(0);
				if (RebaseTodoLine.Action.COMMENT.equals(step.action))
					sb.append(step.getComment());
				else {
					sb.append(step.getAction().toToken());
					sb.append(" ");
					sb.append(step.getCommit().name());
					sb.append(" ");
					sb.append(step.getShortMessage().trim());
				}
				sb.append('\n');
				fw.write(Constants.encode(sb.toString()));
			}
		}
	}
}
