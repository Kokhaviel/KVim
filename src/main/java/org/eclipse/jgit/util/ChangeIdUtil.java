/*
 * Copyright (C) 2010, Robin Rosenberg and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.util;

import java.util.regex.Pattern;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;

public class ChangeIdUtil {

	static final String CHANGE_ID = "Change-Id:";

	@SuppressWarnings("nls")
	static String clean(String msg) {
		return msg.replaceAll("(?i)(?m)^Signed-off-by:.*$\n?", "").
				replaceAll("(?m)^#.*$\n?", "").replaceAll("(?m)\n\n\n+", "\\\n").
				replaceAll("\\n*$", "").replaceAll("(?s)\ndiff --git.*", "").trim();
	}

	public static ObjectId computeChangeId(final ObjectId treeId, final ObjectId firstParentId, final PersonIdent author,
										   final PersonIdent committer, final String message) {
		String cleanMessage = clean(message);
		if(cleanMessage.length() == 0)
			return null;
		StringBuilder b = new StringBuilder();
		b.append("tree ");
		b.append(ObjectId.toString(treeId));
		b.append("\n");
		if(firstParentId != null) {
			b.append("parent ");
			b.append(ObjectId.toString(firstParentId));
			b.append("\n");
		}
		b.append("author ");
		b.append(author.toExternalString()).append("\n").append("committer ");
		b.append(committer.toExternalString()).append("\n\n").append(cleanMessage);
		try(ObjectInserter f = new ObjectInserter.Formatter()) {
			return f.idFor(Constants.OBJ_COMMIT, Constants.encode(b.toString()));
		}
	}

	private static final Pattern issuePattern = Pattern
			.compile("^(Bug|Issue)[a-zA-Z\\d-]*:.*$");

	private static final Pattern footerPattern = Pattern
			.compile("(^[a-zA-Z\\d-]+:(?!//).*$)");

	private static final Pattern changeIdPattern = Pattern
			.compile("(^" + CHANGE_ID + " *I[a-f\\d]{40}$)");

	private static final Pattern includeInFooterPattern = Pattern
			.compile("^[ \\[].*$");

	private static final Pattern trailingWhitespace = Pattern.compile("\\s+$");

	public static String insertId(String message, ObjectId changeId) {
		return insertId(message, changeId, false);
	}

	public static String insertId(String message, ObjectId changeId,
								  boolean replaceExisting) {
		int indexOfChangeId = indexOfChangeId(message, "\n");
		if(indexOfChangeId > 0) {
			if(!replaceExisting) {
				return message;
			}
			StringBuilder ret = new StringBuilder(
					message.substring(0, indexOfChangeId));
			ret.append(CHANGE_ID);
			ret.append(" I");
			ret.append(ObjectId.toString(changeId));
			int indexOfNextLineBreak = message.indexOf('\n',
					indexOfChangeId);
			if(indexOfNextLineBreak > 0)
				ret.append(message.substring(indexOfNextLineBreak));
			return ret.toString();
		}

		String[] lines = message.split("\n");
		int footerFirstLine = indexOfFirstFooterLine(lines);
		int insertAfter = footerFirstLine;
		for(int i = footerFirstLine; i < lines.length; ++i) {
			if(issuePattern.matcher(lines[i]).matches()) {
				insertAfter = i + 1;
				continue;
			}
			break;
		}
		StringBuilder ret = new StringBuilder();
		int i = 0;
		for(; i < insertAfter; ++i) {
			ret.append(lines[i]);
			ret.append("\n");
		}
		if(insertAfter == lines.length && insertAfter == footerFirstLine)
			ret.append("\n");
		ret.append(CHANGE_ID);
		ret.append(" I");
		ret.append(ObjectId.toString(changeId));
		ret.append("\n");
		for(; i < lines.length; ++i) {
			ret.append(lines[i]);
			ret.append("\n");
		}
		return ret.toString();
	}

	public static int indexOfChangeId(String message, String delimiter) {
		String[] lines = message.split(delimiter);
		if(lines.length == 0)
			return -1;
		int indexOfChangeIdLine = 0;
		boolean inFooter = false;
		for(int i = lines.length - 1; i >= 0; --i) {
			if(!inFooter && isEmptyLine(lines[i]))
				continue;
			inFooter = true;
			if(changeIdPattern.matcher(trimRight(lines[i])).matches()) {
				indexOfChangeIdLine = i;
				break;
			} else if(isEmptyLine(lines[i]) || i == 0)
				return -1;
		}
		int indexOfChangeIdLineinString = 0;
		for(int i = 0; i < indexOfChangeIdLine; ++i)
			indexOfChangeIdLineinString += lines[i].length()
					+ delimiter.length();
		return indexOfChangeIdLineinString
				+ lines[indexOfChangeIdLine].indexOf(CHANGE_ID);
	}

	private static boolean isEmptyLine(String line) {
		return line.trim().length() == 0;
	}

	private static String trimRight(String s) {
		return trailingWhitespace.matcher(s).replaceAll("");
	}

	public static int indexOfFirstFooterLine(String[] lines) {
		int footerFirstLine = lines.length;
		for(int i = lines.length - 1; i > 1; --i) {
			if(footerPattern.matcher(lines[i]).matches()) {
				footerFirstLine = i;
				continue;
			}
			if(footerFirstLine != lines.length && lines[i].length() == 0)
				break;
			if(footerFirstLine != lines.length
					&& includeInFooterPattern.matcher(lines[i]).matches()) {
				footerFirstLine = i + 1;
				continue;
			}
			footerFirstLine = lines.length;
			break;
		}
		return footerFirstLine;
	}
}
