/*
 * Copyright (C) 2012 Christian Halstrick and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.util;

import java.text.MessageFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.eclipse.jgit.internal.JGitText;

public class GitDateParser {

	public static final Date NEVER = new Date(Long.MAX_VALUE);

	private static final ThreadLocal<Map<Locale, Map<ParseableSimpleDateFormat, SimpleDateFormat>>> formatCache =
			ThreadLocal.withInitial(HashMap::new);

	private static SimpleDateFormat getDateFormat(ParseableSimpleDateFormat f,
												  Locale locale) {
		Map<Locale, Map<ParseableSimpleDateFormat, SimpleDateFormat>> cache = formatCache
				.get();
		Map<ParseableSimpleDateFormat, SimpleDateFormat> map = cache
				.get(locale);
		if(map == null) {
			map = new HashMap<>();
			cache.put(locale, map);
			return getNewSimpleDateFormat(f, locale, map);
		}
		SimpleDateFormat dateFormat = map.get(f);
		if(dateFormat != null)
			return dateFormat;
		return getNewSimpleDateFormat(f, locale, map);
	}

	private static SimpleDateFormat getNewSimpleDateFormat(
			ParseableSimpleDateFormat f, Locale locale,
			Map<ParseableSimpleDateFormat, SimpleDateFormat> map) {
		SimpleDateFormat df = SystemReader.getInstance().getSimpleDateFormat(
				f.formatStr, locale);
		map.put(f, df);
		return df;
	}

	enum ParseableSimpleDateFormat {
		ISO("yyyy-MM-dd HH:mm:ss Z"),
		RFC("EEE, dd MMM yyyy HH:mm:ss Z"),
		SHORT("yyyy-MM-dd"),
		SHORT_WITH_DOTS_REVERSE("dd.MM.yyyy"),
		SHORT_WITH_DOTS("yyyy.MM.dd"),
		SHORT_WITH_SLASH("MM/dd/yyyy"),
		DEFAULT("EEE MMM dd HH:mm:ss yyyy Z"),
		LOCAL("EEE MMM dd HH:mm:ss yyyy");

		private final String formatStr;

		ParseableSimpleDateFormat(String formatStr) {
			this.formatStr = formatStr;
		}
	}

	public static Date parse(String dateStr, Calendar now, Locale locale)
			throws ParseException {
		dateStr = dateStr.trim();
		Date ret;

		if("never".equalsIgnoreCase(dateStr))
			return NEVER;
		ret = parse_relative(dateStr, now);
		if(ret != null)
			return ret;
		for(ParseableSimpleDateFormat f : ParseableSimpleDateFormat.values()) {
			try {
				return parse_simple(dateStr, f, locale);
			} catch(ParseException ignored) {
			}
		}
		ParseableSimpleDateFormat[] values = ParseableSimpleDateFormat.values();
		StringBuilder allFormats = new StringBuilder("\"")
				.append(values[0].formatStr);
		for(int i = 1; i < values.length; i++)
			allFormats.append("\", \"").append(values[i].formatStr);
		allFormats.append("\"");
		throw new ParseException(MessageFormat.format(
				JGitText.get().cannotParseDate, dateStr, allFormats.toString()), 0);
	}

	private static Date parse_simple(String dateStr,
									 ParseableSimpleDateFormat f, Locale locale)
			throws ParseException {
		SimpleDateFormat dateFormat = getDateFormat(f, locale);
		dateFormat.setLenient(false);
		return dateFormat.parse(dateStr);
	}

	@SuppressWarnings("nls")
	private static Date parse_relative(String dateStr, Calendar now) {
		Calendar cal;
		SystemReader sysRead = SystemReader.getInstance();

		if("now".equals(dateStr)) {
			return ((now == null) ? new Date(sysRead.getCurrentTime()) : now
					.getTime());
		}

		if(now == null) {
			cal = new GregorianCalendar(sysRead.getTimeZone(),
					sysRead.getLocale());
			cal.setTimeInMillis(sysRead.getCurrentTime());
		} else
			cal = (Calendar) now.clone();

		if("yesterday".equals(dateStr)) {
			cal.add(Calendar.DATE, -1);
			cal.set(Calendar.HOUR_OF_DAY, 0);
			cal.set(Calendar.MINUTE, 0);
			cal.set(Calendar.SECOND, 0);
			cal.set(Calendar.MILLISECOND, 0);
			cal.set(Calendar.MILLISECOND, 0);
			return cal.getTime();
		}

		String[] parts = dateStr.split("[. ]");
		int partsLength = parts.length;
		if(partsLength < 3 || (partsLength & 1) == 0
				|| !"ago".equals(parts[parts.length - 1]))
			return null;
		int number;
		for(int i = 0; i < parts.length - 2; i += 2) {
			try {
				number = Integer.parseInt(parts[i]);
			} catch(NumberFormatException e) {
				return null;
			}
			if(parts[i + 1] == null) {
				return null;
			}
			switch(parts[i + 1]) {
				case "year":
				case "years":
					cal.add(Calendar.YEAR, -number);
					break;
				case "month":
				case "months":
					cal.add(Calendar.MONTH, -number);
					break;
				case "week":
				case "weeks":
					cal.add(Calendar.WEEK_OF_YEAR, -number);
					break;
				case "day":
				case "days":
					cal.add(Calendar.DATE, -number);
					break;
				case "hour":
				case "hours":
					cal.add(Calendar.HOUR_OF_DAY, -number);
					break;
				case "minute":
				case "minutes":
					cal.add(Calendar.MINUTE, -number);
					break;
				case "second":
				case "seconds":
					cal.add(Calendar.SECOND, -number);
					break;
				default:
					return null;
			}
		}
		return cal.getTime();
	}
}
