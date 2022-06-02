/*
 * Copyright (C) 2009-2022, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.util;

import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.internal.JGitText;

import java.text.MessageFormat;
import java.util.Collection;

public final class StringUtils {

	private static final long KiB = 1024;

	private static final long MiB = 1024 * KiB;

	private static final long GiB = 1024 * MiB;

	private static final char[] LC;

	static {
		LC = new char['Z' + 1];
		for(char c = 0; c < LC.length; c++)
			LC[c] = c;
		for(char c = 'A'; c <= 'Z'; c++)
			LC[c] = (char) ('a' + (c - 'A'));
	}

	public static char toLowerCase(char c) {
		return c <= 'Z' ? LC[c] : c;
	}

	public static String toLowerCase(String in) {
		final StringBuilder r = new StringBuilder(in.length());
		for(int i = 0; i < in.length(); i++)
			r.append(toLowerCase(in.charAt(i)));
		return r.toString();
	}

	public static boolean equalsIgnoreCase(String a, String b) {
		if(References.isSameObject(a, b)) {
			return true;
		}
		if(a.length() != b.length())
			return false;
		for(int i = 0; i < a.length(); i++) {
			if(toLowerCase(a.charAt(i)) != toLowerCase(b.charAt(i)))
				return false;
		}
		return true;
	}

	public static int compareIgnoreCase(String a, String b) {
		for(int i = 0; i < a.length() && i < b.length(); i++) {
			int d = toLowerCase(a.charAt(i)) - toLowerCase(b.charAt(i));
			if(d != 0)
				return d;
		}
		return a.length() - b.length();
	}

	public static int compareWithCase(String a, String b) {
		for(int i = 0; i < a.length() && i < b.length(); i++) {
			int d = a.charAt(i) - b.charAt(i);
			if(d != 0)
				return d;
		}
		return a.length() - b.length();
	}

	public static boolean toBoolean(String stringValue) {
		if(stringValue == null)
			throw new NullPointerException(JGitText.get().expectedBooleanStringValue);

		final Boolean bool = toBooleanOrNull(stringValue);
		if(bool == null)
			throw new IllegalArgumentException(MessageFormat.format(JGitText.get().notABoolean, stringValue));

		return bool;
	}

	public static Boolean toBooleanOrNull(String stringValue) {
		if(stringValue == null)
			return null;

		if(equalsIgnoreCase("yes", stringValue)
				|| equalsIgnoreCase("true", stringValue)
				|| equalsIgnoreCase("1", stringValue)
				|| equalsIgnoreCase("on", stringValue))
			return Boolean.TRUE;
		else if(equalsIgnoreCase("no", stringValue)
				|| equalsIgnoreCase("false", stringValue)
				|| equalsIgnoreCase("0", stringValue)
				|| equalsIgnoreCase("off", stringValue))
			return Boolean.FALSE;
		else
			return null;
	}

	public static String join(Collection<String> parts, String separator) {
		return StringUtils.join(parts, separator, separator);
	}

	public static String join(Collection<String> parts, String separator,
							  String lastSeparator) {
		StringBuilder sb = new StringBuilder();
		int i = 0;
		int lastIndex = parts.size() - 1;
		for(String part : parts) {
			sb.append(part);
			if(i == lastIndex - 1) {
				sb.append(lastSeparator);
			} else if(i != lastIndex) {
				sb.append(separator);
			}
			i++;
		}
		return sb.toString();
	}

	public static boolean isEmptyOrNull(String stringValue) {
		return stringValue == null || stringValue.length() == 0;
	}

	public static String replaceLineBreaksWithSpace(String in) {
		char[] buf = new char[in.length()];
		int o = 0;
		for(int i = 0; i < buf.length; ++i) {
			char ch = in.charAt(i);
			switch(ch) {
				case '\r':
					if(i + 1 < buf.length && in.charAt(i + 1) == '\n') {
						buf[o++] = ' ';
						++i;
					} else
						buf[o++] = ' ';
					break;
				case '\n':
					buf[o++] = ' ';
					break;
				default:
					buf[o++] = ch;
					break;
			}
		}
		return new String(buf, 0, o);
	}

	public static long parseLongWithSuffix(@NonNull String value,
										   boolean positiveOnly)
			throws NumberFormatException, StringIndexOutOfBoundsException {
		String n = value.trim();
		if(n.isEmpty()) {
			throw new StringIndexOutOfBoundsException();
		}
		long mul = 1;
		switch(n.charAt(n.length() - 1)) {
			case 'g':
			case 'G':
				mul = GiB;
				break;
			case 'm':
			case 'M':
				mul = MiB;
				break;
			case 'k':
			case 'K':
				mul = KiB;
				break;
			default:
				break;
		}
		if(mul > 1) {
			n = n.substring(0, n.length() - 1).trim();
		}
		if(n.isEmpty()) {
			throw new StringIndexOutOfBoundsException();
		}
		long number;
		if(positiveOnly) {
			number = Long.parseUnsignedLong(n);
			if(number < 0) {
				throw new NumberFormatException(
						MessageFormat.format(JGitText.get().valueExceedsRange,
								value, Long.class.getSimpleName()));
			}
		} else {
			number = Long.parseLong(n);
		}
		if(mul == 1) {
			return number;
		}
		try {
			return Math.multiplyExact(mul, number);
		} catch(ArithmeticException e) {
			NumberFormatException nfe = new NumberFormatException(
					e.getLocalizedMessage());
			nfe.initCause(e);
			throw nfe;
		}
	}

	public static String formatWithSuffix(long value) {
		if(value >= GiB && (value % GiB) == 0) {
			return String.valueOf(value / GiB) + 'g';
		}
		if(value >= MiB && (value % MiB) == 0) {
			return String.valueOf(value / MiB) + 'm';
		}
		if(value >= KiB && (value % KiB) == 0) {
			return String.valueOf(value / KiB) + 'k';
		}
		return String.valueOf(value);
	}
}
