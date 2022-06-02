/*
 * Copyright (C) 2011, Matthias Sohn <matthias.sohn@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.util;

import java.text.MessageFormat;
import java.util.Date;

import org.eclipse.jgit.internal.JGitText;

public class RelativeDateFormatter {
	static final long SECOND_IN_MILLIS = 1000;

	static final long MINUTE_IN_MILLIS = 60 * SECOND_IN_MILLIS;

	static final long HOUR_IN_MILLIS = 60 * MINUTE_IN_MILLIS;

	static final long DAY_IN_MILLIS = 24 * HOUR_IN_MILLIS;

	static final long WEEK_IN_MILLIS = 7 * DAY_IN_MILLIS;

	static final long MONTH_IN_MILLIS = 30 * DAY_IN_MILLIS;

	static final long YEAR_IN_MILLIS = 365 * DAY_IN_MILLIS;

	@SuppressWarnings("boxing")
	public static String format(Date when) {

		long ageMillis = SystemReader.getInstance().getCurrentTime()
				- when.getTime();

		if(ageMillis < 0)
			return JGitText.get().inTheFuture;

		if(ageMillis < upperLimit(MINUTE_IN_MILLIS))
			return MessageFormat.format(JGitText.get().secondsAgo,
					round(ageMillis, SECOND_IN_MILLIS));

		if(ageMillis < upperLimit(HOUR_IN_MILLIS))
			return MessageFormat.format(JGitText.get().minutesAgo,
					round(ageMillis, MINUTE_IN_MILLIS));

		if(ageMillis < upperLimit(DAY_IN_MILLIS))
			return MessageFormat.format(JGitText.get().hoursAgo,
					round(ageMillis, HOUR_IN_MILLIS));

		if(ageMillis < 14 * DAY_IN_MILLIS)
			return MessageFormat.format(JGitText.get().daysAgo,
					round(ageMillis, DAY_IN_MILLIS));

		if(ageMillis < 10 * WEEK_IN_MILLIS)
			return MessageFormat.format(JGitText.get().weeksAgo,
					round(ageMillis, WEEK_IN_MILLIS));

		if(ageMillis < YEAR_IN_MILLIS)
			return MessageFormat.format(JGitText.get().monthsAgo,
					round(ageMillis, MONTH_IN_MILLIS));

		if(ageMillis < 5 * YEAR_IN_MILLIS) {
			long years = round(ageMillis, MONTH_IN_MILLIS) / 12;
			String yearLabel = (years > 1) ? JGitText.get().years :
					JGitText.get().year;
			long months = round(ageMillis - years * YEAR_IN_MILLIS,
					MONTH_IN_MILLIS);
			String monthLabel = (months > 1) ? JGitText.get().months :
					(months == 1 ? JGitText.get().month : "");
			return MessageFormat.format(
					months == 0 ? JGitText.get().years0MonthsAgo : JGitText
							.get().yearsMonthsAgo,
					years, yearLabel, months, monthLabel);
		}

		return MessageFormat.format(JGitText.get().yearsAgo,
				round(ageMillis, YEAR_IN_MILLIS));
	}

	private static long upperLimit(long unit) {
		return unit + unit / 2;
	}

	private static long round(long n, long unit) {
		return (n + unit / 2) / unit;
	}
}
