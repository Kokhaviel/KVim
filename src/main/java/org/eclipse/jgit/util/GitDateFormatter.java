/*
 * Copyright (C) 2011, 2012 Robin Rosenberg and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.util;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

import org.eclipse.jgit.lib.PersonIdent;

public class GitDateFormatter {

	private DateFormat dateTimeInstance;

	private DateFormat dateTimeInstance2;

	private final Format format;

	public enum Format {
		DEFAULT,
		RELATIVE,
		LOCAL,
		ISO,
		RFC,
		SHORT,
		RAW,
		LOCALE,
		LOCALELOCAL
	}

	public GitDateFormatter(Format format) {
		this.format = format;
		switch(format) {
			default:
				break;
			case DEFAULT:
				dateTimeInstance = new SimpleDateFormat(
						"EEE MMM dd HH:mm:ss yyyy Z", Locale.US);
				break;
			case ISO:
				dateTimeInstance = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z",
						Locale.US);
				break;
			case LOCAL:
				dateTimeInstance = new SimpleDateFormat("EEE MMM dd HH:mm:ss yyyy",
						Locale.US);
				break;
			case RFC:
				dateTimeInstance = new SimpleDateFormat(
						"EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);
				break;
			case SHORT:
				dateTimeInstance = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
				break;
			case LOCALE:
			case LOCALELOCAL:
				SystemReader systemReader = SystemReader.getInstance();
				dateTimeInstance = systemReader.getDateTimeInstance(
						DateFormat.DEFAULT, DateFormat.DEFAULT);
				dateTimeInstance2 = systemReader.getSimpleDateFormat("Z");
				break;
		}
	}

	public String formatDate(PersonIdent ident) {
		switch(format) {
			case RAW:
				int offset = ident.getTimeZoneOffset();
				String sign = offset < 0 ? "-" : "+";
				int offset2;
				if(offset < 0)
					offset2 = -offset;
				else
					offset2 = offset;
				int hours = offset2 / 60;
				int minutes = offset2 % 60;
				return String.format("%d %s%02d%02d",
						ident.getWhen().getTime() / 1000, sign, hours, minutes);
			case RELATIVE:
				return RelativeDateFormatter.format(ident.getWhen());
			case LOCALELOCAL:
			case LOCAL:
				dateTimeInstance.setTimeZone(SystemReader.getInstance()
						.getTimeZone());
				return dateTimeInstance.format(ident.getWhen());
			case LOCALE:
				TimeZone tz = ident.getTimeZone();
				if(tz == null)
					tz = SystemReader.getInstance().getTimeZone();
				dateTimeInstance.setTimeZone(tz);
				dateTimeInstance2.setTimeZone(tz);
				return dateTimeInstance.format(ident.getWhen()) + " "
						+ dateTimeInstance2.format(ident.getWhen());
			default:
				dateTimeInstance.setTimeZone(ident.getTimeZone());
				return dateTimeInstance.format(ident.getWhen());
		}
	}
}
