/*
 * Copyright (C) 2014, 2017 Andrey Loskutov <loskutov@gmx.de> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.ignore.internal;

import static java.lang.Character.isLetter;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.eclipse.jgit.errors.InvalidPatternException;
import org.eclipse.jgit.ignore.FastIgnoreRule;
import org.eclipse.jgit.internal.JGitText;

public class Strings {

	static char getPathSeparator(Character pathSeparator) {
		return pathSeparator == null ? FastIgnoreRule.PATH_SEPARATOR : pathSeparator;
	}

	public static String stripTrailing(String pattern, char c) {
		for(int i = pattern.length() - 1; i >= 0; i--) {
			char charAt = pattern.charAt(i);
			if(charAt != c) {
				if(i == pattern.length() - 1) {
					return pattern;
				}
				return pattern.substring(0, i + 1);
			}
		}
		return "";
	}

	public static String stripTrailingWhitespace(String pattern) {
		for(int i = pattern.length() - 1; i >= 0; i--) {
			char charAt = pattern.charAt(i);
			if(!Character.isWhitespace(charAt)) {
				if(i == pattern.length() - 1) {
					return pattern;
				}
				return pattern.substring(0, i + 1);
			}
		}
		return "";
	}

	public static boolean isDirectoryPattern(String pattern) {
		for(int i = pattern.length() - 1; i >= 0; i--) {
			char charAt = pattern.charAt(i);
			if(!Character.isWhitespace(charAt)) {
				return charAt == FastIgnoreRule.PATH_SEPARATOR;
			}
		}
		return false;
	}

	static int count(String s, char c, boolean ignoreFirstLast) {
		int start = 0;
		int count = 0;
		int length = s.length();
		while(start < length) {
			start = s.indexOf(c, start);
			if(start == -1) {
				break;
			}
			if(!ignoreFirstLast || (start != 0 && start != length - 1)) {
				count++;
			}
			start++;
		}
		return count;
	}

	public static List<String> split(String pattern, char slash) {
		int count = count(pattern, slash, true);
		if(count < 1)
			throw new IllegalStateException(
					"Pattern must have at least two segments: " + pattern);
		List<String> segments = new ArrayList<>(count);
		int right = 0;
		while(true) {
			int left = right;
			right = pattern.indexOf(slash, right);
			if(right == -1) {
				if(left < pattern.length())
					segments.add(pattern.substring(left));
				break;
			}
			if(right - left > 0)
				if(left == 1) segments.add(pattern.substring(0, right));
				else if(right == pattern.length() - 1) segments.add(pattern.substring(left, right + 1));
				else segments.add(pattern.substring(left, right));
			right++;
		}
		return segments;
	}

	static boolean isWildCard(String pattern) {
		return pattern.indexOf('*') != -1 || isComplexWildcard(pattern);
	}

	private static boolean isComplexWildcard(String pattern) {
		int idx1 = pattern.indexOf('[');
		if(idx1 != -1) {
			return true;
		}
		if(pattern.indexOf('?') != -1) {
			return true;
		}

		int backSlash = pattern.indexOf('\\');
		if(backSlash >= 0) {
			int nextIdx = backSlash + 1;
			if(pattern.length() == nextIdx) {
				return false;
			}
			char nextChar = pattern.charAt(nextIdx);
			return escapedByBackslash(nextChar);
		}
		return false;
	}

	private static boolean escapedByBackslash(char nextChar) {
		return nextChar == '?' || nextChar == '*' || nextChar == '[';
	}

	static PatternState checkWildCards(String pattern) {
		if(isComplexWildcard(pattern))
			return PatternState.COMPLEX;
		int startIdx = pattern.indexOf('*');
		if(startIdx < 0)
			return PatternState.NONE;

		if(startIdx == pattern.length() - 1)
			return PatternState.TRAILING_ASTERISK_ONLY;
		if(pattern.lastIndexOf('*') == 0)
			return PatternState.LEADING_ASTERISK_ONLY;

		return PatternState.COMPLEX;
	}

	enum PatternState {
		LEADING_ASTERISK_ONLY, TRAILING_ASTERISK_ONLY, COMPLEX, NONE
	}

	static final List<String> POSIX_CHAR_CLASSES = Arrays.asList(
			"alnum", "alpha", "blank", "cntrl",
			"digit", "graph", "lower", "print",
			"punct", "space", "upper", "xdigit",
			"word");

	private static final String DL = "\\p{javaDigit}\\p{javaLetter}";

	static final List<String> JAVA_CHAR_CLASSES = Arrays
			.asList("\\p{Alnum}", "\\p{javaLetter}", "\\p{Blank}", "\\p{Cntrl}",
					"\\p{javaDigit}", "[\\p{Graph}" + DL + "]", "\\p{Ll}", "[\\p{Print}" + DL + "]",
					"\\p{Punct}", "\\p{Space}", "\\p{Lu}", "\\p{XDigit}",
					"[" + DL + "_]");

	static final Pattern UNSUPPORTED = Pattern.compile("\\[\\[[.=]\\w+[.=]\\]\\]");

	static Pattern convertGlob(String pattern) throws InvalidPatternException {
		if(UNSUPPORTED.matcher(pattern).find())
			throw new InvalidPatternException(
					"Collating symbols [[.a.]] or equivalence class expressions [[=a=]] are not supported",
					pattern);

		StringBuilder sb = new StringBuilder(pattern.length());

		int in_brackets = 0;
		boolean seenEscape = false;
		boolean ignoreLastBracket = false;
		boolean in_char_class = false;
		char[] charClass = new char[6];

		for(int i = 0; i < pattern.length(); i++) {
			final char c = pattern.charAt(i);
			switch(c) {

				case '*':
					if(seenEscape || in_brackets > 0)
						sb.append(c);
					else
						sb.append('.').append(c);
					break;

				case '(':
				case ')':
				case '{':
				case '}':
				case '+':
				case '$':
				case '^':
				case '|':
					if(seenEscape || in_brackets > 0)
						sb.append(c);
					else
						sb.append('\\').append(c);
					break;

				case '.':
					if(seenEscape)
						sb.append(c);
					else
						sb.append('\\').append('.');
					break;

				case '?':
					if(seenEscape || in_brackets > 0)
						sb.append(c);
					else
						sb.append('.');
					break;

				case ':':
					if(in_brackets > 0)
						if(lookBehind(sb) == '['
								&& isLetter(lookAhead(pattern, i)))
							in_char_class = true;
					sb.append(':');
					break;

				case '-':
					if(in_brackets > 0) {
						if(lookAhead(pattern, i) == ']')
							sb.append('\\').append(c);
						else
							sb.append(c);
					} else
						sb.append('-');
					break;

				case '\\':
					if(in_brackets > 0) {
						char lookAhead = lookAhead(pattern, i);
						if(lookAhead == ']' || lookAhead == '[')
							ignoreLastBracket = true;
					} else {
						char lookAhead = lookAhead(pattern, i);
						if(lookAhead != '\\' && lookAhead != '['
								&& lookAhead != '?' && lookAhead != '*'
								&& lookAhead != ' ' && lookBehind(sb) != '\\') {
							break;
						}
					}
					sb.append(c);
					break;

				case '[':
					if(in_brackets > 0) {
						if(!seenEscape) {
							sb.append('\\');
						}
						sb.append('[');
						ignoreLastBracket = true;
					} else {
						if(!seenEscape) {
							in_brackets++;
							ignoreLastBracket = false;
						}
						sb.append('[');
					}
					break;

				case ']':
					if(seenEscape) {
						sb.append(']');
						ignoreLastBracket = true;
						break;
					}
					if(in_brackets <= 0) {
						sb.append('\\').append(']');
						ignoreLastBracket = true;
						break;
					}
					char lookBehind = lookBehind(sb);
					if((lookBehind == '[' && !ignoreLastBracket)
							|| lookBehind == '^') {
						sb.append('\\');
						sb.append(']');
						ignoreLastBracket = true;
					} else {
						ignoreLastBracket = false;
						if(!in_char_class) {
							in_brackets--;
							sb.append(']');
						} else {
							in_char_class = false;
							String charCl = checkPosixCharClass(charClass);
							if(charCl != null) {
								sb.setLength(sb.length() - 4);
								sb.append(charCl);
							}
							reset(charClass);
						}
					}
					break;

				case '!':
					if(in_brackets > 0) {
						if(lookBehind(sb) == '[')
							sb.append('^');
						else
							sb.append(c);
					} else
						sb.append(c);
					break;

				default:
					if(in_char_class)
						setNext(charClass, c);
					else
						sb.append(c);
					break;
			}

			seenEscape = c == '\\';

		}

		if(in_brackets > 0)
			throw new InvalidPatternException("Not closed bracket?", pattern);
		try {
			return Pattern.compile(sb.toString(), Pattern.DOTALL);
		} catch(PatternSyntaxException e) {
			throw new InvalidPatternException(
					MessageFormat.format(JGitText.get().invalidIgnoreRule,
							pattern),
					pattern, e);
		}
	}

	private static char lookBehind(StringBuilder buffer) {
		return buffer.length() > 0 ? buffer.charAt(buffer.length() - 1) : 0;
	}

	private static char lookAhead(String pattern, int i) {
		int idx = i + 1;
		return idx >= pattern.length() ? 0 : pattern.charAt(idx);
	}

	private static void setNext(char[] buffer, char c) {
		for(int i = 0; i < buffer.length; i++)
			if(buffer[i] == 0) {
				buffer[i] = c;
				break;
			}
	}

	private static void reset(char[] buffer) {
		Arrays.fill(buffer, (char) 0);
	}

	private static String checkPosixCharClass(char[] buffer) {
		for(int i = 0; i < POSIX_CHAR_CLASSES.size(); i++) {
			String clazz = POSIX_CHAR_CLASSES.get(i);
			boolean match = true;
			for(int j = 0; j < clazz.length(); j++)
				if(buffer[j] != clazz.charAt(j)) {
					match = false;
					break;
				}
			if(match)
				return JAVA_CHAR_CLASSES.get(i);
		}
		return null;
	}

	static String deleteBackslash(String s) {
		if(s.indexOf('\\') < 0) {
			return s;
		}
		StringBuilder sb = new StringBuilder(s.length());
		for(int i = 0; i < s.length(); i++) {
			char ch = s.charAt(i);
			if(ch == '\\') {
				if(i + 1 == s.length()) {
					continue;
				}
				char next = s.charAt(i + 1);
				if(next == '\\') {
					sb.append(ch);
					i++;
					continue;
				}
				if(!escapedByBackslash(next)) {
					continue;
				}
			}
			sb.append(ch);
		}
		return sb.toString();
	}

}
