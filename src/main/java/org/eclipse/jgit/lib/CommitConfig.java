/*
 * Copyright (c) 2020, 2022 Julian Ruppel <julian.ruppel@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.lib;

import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.lib.Config.ConfigEnum;
import org.eclipse.jgit.util.StringUtils;

import java.util.Locale;

public class CommitConfig {

	public static final Config.SectionParser<CommitConfig> KEY = CommitConfig::new;
	private static final String CUT = " ------------------------ >8 ------------------------\n";
	private static final char[] COMMENT_CHARS = {'#', ';', '@', '!', '$', '%', '^', '&', '|', ':'};

	public enum CleanupMode implements ConfigEnum {
		STRIP,
		WHITESPACE,
		VERBATIM,
		SCISSORS,
		DEFAULT;

		@Override
		public String toConfigValue() {
			return name().toLowerCase(Locale.ROOT);
		}

		@Override
		public boolean matchConfigValue(String in) {
			return toConfigValue().equals(in);
		}
	}

	private final CleanupMode cleanupMode;

	private char commentCharacter = '#';

	private boolean autoCommentChar = false;

	private CommitConfig(Config rc) {
		rc.getString(ConfigConstants.CONFIG_COMMIT_SECTION,
				null, ConfigConstants.CONFIG_KEY_COMMIT_TEMPLATE);
		rc.getString(ConfigConstants.CONFIG_SECTION_I18N,
				null, ConfigConstants.CONFIG_KEY_COMMIT_ENCODING);
		cleanupMode = rc.getEnum(ConfigConstants.CONFIG_COMMIT_SECTION, null,
				ConfigConstants.CONFIG_KEY_CLEANUP, CleanupMode.DEFAULT);
		String comment = rc.getString(ConfigConstants.CONFIG_CORE_SECTION, null,
				ConfigConstants.CONFIG_KEY_COMMENT_CHAR);
		if(!StringUtils.isEmptyOrNull(comment)) {
			if("auto".equalsIgnoreCase(comment)) {
				autoCommentChar = true;
			} else {
				char first = comment.charAt(0);
				if(first > ' ' && first < 127) {
					commentCharacter = first;
				}
			}
		}
	}

	public char getCommentChar() {
		return commentCharacter;
	}

	public char getCommentChar(String text) {
		if(isAutoCommentChar()) {
			char toUse = determineCommentChar(text);
			if(toUse > 0) {
				return toUse;
			}
			return '#';
		}
		return getCommentChar();
	}

	public boolean isAutoCommentChar() {
		return autoCommentChar;
	}

	@NonNull
	public CleanupMode getCleanupMode() {
		return cleanupMode;
	}

	@NonNull
	public CleanupMode resolve(@NonNull CleanupMode mode,
							   boolean defaultStrip) {
		if(CleanupMode.DEFAULT == mode) {
			CleanupMode defaultMode = getCleanupMode();
			if(CleanupMode.DEFAULT == defaultMode) {
				return defaultStrip ? CleanupMode.STRIP
						: CleanupMode.WHITESPACE;
			}
			return defaultMode;
		}
		return mode;
	}

	public static String cleanText(@NonNull String text,
								   @NonNull CleanupMode mode, char commentChar) {
		String toProcess = text;
		boolean strip = false;
		switch(mode) {
			case VERBATIM:
				return text;
			case SCISSORS:
				String cut = commentChar + CUT;
				if(text.startsWith(cut)) {
					return "";
				}
				int cutPos = text.indexOf('\n' + cut);
				if(cutPos >= 0) {
					toProcess = text.substring(0, cutPos + 1);
				}
				break;
			case STRIP:
				strip = true;
				break;
			case WHITESPACE:
				break;
			case DEFAULT:
			default:
				throw new IllegalArgumentException("Invalid clean-up mode " + mode);
		}

		StringBuilder result = new StringBuilder();
		boolean lastWasEmpty = true;
		for(String line : toProcess.split("\n")) {
			line = line.trim();
			if(line.isEmpty()) {
				if(!lastWasEmpty) {
					result.append('\n');
					lastWasEmpty = true;
				}
			} else if(!strip || !isComment(line, commentChar)) {
				lastWasEmpty = false;
				result.append(line).append('\n');
			}
		}
		int bufferSize = result.length();
		if(lastWasEmpty && bufferSize > 0) {
			bufferSize--;
			result.setLength(bufferSize);
		}
		if(bufferSize > 0 && !toProcess.endsWith("\n")) {
			if(result.charAt(bufferSize - 1) == '\n') {
				result.setLength(bufferSize - 1);
			}
		}
		return result.toString();
	}

	private static boolean isComment(String text, char commentChar) {
		int len = text.length();
		for(int i = 0; i < len; i++) {
			char ch = text.charAt(i);
			if(!Character.isWhitespace(ch)) {
				return ch == commentChar;
			}
		}
		return false;
	}

	public static char determineCommentChar(String text) {
		if(StringUtils.isEmptyOrNull(text)) {
			return '#';
		}
		final boolean[] inUse = new boolean[127];
		for(String line : text.split("\n")) {
			int len = line.length();
			for(int i = 0; i < len; i++) {
				char ch = line.charAt(i);
				if(!Character.isWhitespace(ch)) {
					if(ch < inUse.length) {
						inUse[ch] = true;
					}
					break;
				}
			}
		}
		for(char candidate : COMMENT_CHARS) {
			if(!inUse[candidate]) {
				return candidate;
			}
		}
		return (char) 0;
	}
}