/*
 * Copyright (C) 2007, Dave Watson <dwatson@mimvista.com>
 * Copyright (C) 2007, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2006-2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.lib;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.util.SystemReader;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class PersonIdent implements Serializable {
	private static final long serialVersionUID = 1L;

	public static TimeZone getTimeZone(int tzOffset) {
		StringBuilder tzId = new StringBuilder(8);
		tzId.append("GMT");
		appendTimezone(tzId, tzOffset);
		return TimeZone.getTimeZone(tzId.toString());
	}

	public static void appendTimezone(StringBuilder r, int offset) {
		final char sign;
		final int offsetHours;
		final int offsetMins;

		if(offset < 0) {
			sign = '-';
			offset = -offset;
		} else {
			sign = '+';
		}

		offsetHours = offset / 60;
		offsetMins = offset % 60;

		r.append(sign);
		if(offsetHours < 10) {
			r.append('0');
		}
		r.append(offsetHours);
		if(offsetMins < 10) {
			r.append('0');
		}
		r.append(offsetMins);
	}

	public static void appendSanitized(StringBuilder r, String str) {
		int i = 0;
		while(i < str.length() && str.charAt(i) <= ' ') {
			i++;
		}
		int end = str.length();
		while(end > i && str.charAt(end - 1) <= ' ') {
			end--;
		}

		for(; i < end; i++) {
			char c = str.charAt(i);
			switch(c) {
				case '\n':
				case '<':
				case '>':
					continue;
				default:
					r.append(c);
					break;
			}
		}
	}

	private final String name;
	private final String emailAddress;
	private final long when;
	private final int tzOffset;

	public PersonIdent(Repository repo) {
		this(repo.getConfig().get(UserConfig.KEY));
	}

	public PersonIdent(PersonIdent pi) {
		this(pi.getName(), pi.getEmailAddress());
	}

	public PersonIdent(String aName, String aEmailAddress) {
		this(aName, aEmailAddress, SystemReader.getInstance().getCurrentTime());
	}

	public PersonIdent(PersonIdent pi, Date when, TimeZone tz) {
		this(pi.getName(), pi.getEmailAddress(), when, tz);
	}

	public PersonIdent(PersonIdent pi, Date aWhen) {
		this(pi.getName(), pi.getEmailAddress(), aWhen.getTime(), pi.tzOffset);
	}

	public PersonIdent(PersonIdent pi, Instant aWhen) {
		this(pi.getName(), pi.getEmailAddress(), aWhen.toEpochMilli(), pi.tzOffset);
	}

	public PersonIdent(final String aName, final String aEmailAddress,
					   final Date aWhen, final TimeZone aTZ) {
		this(aName, aEmailAddress, aWhen.getTime(), aTZ.getOffset(aWhen
				.getTime()) / (60 * 1000));
	}

	public PersonIdent(final String aName, String aEmailAddress, Instant aWhen,
					   ZoneId zoneId) {
		this(aName, aEmailAddress, aWhen.toEpochMilli(),
				TimeZone.getTimeZone(zoneId)
						.getOffset(aWhen
								.toEpochMilli()) / (60 * 1000));
	}

	public PersonIdent(PersonIdent pi, long aWhen, int aTZ) {
		this(pi.getName(), pi.getEmailAddress(), aWhen, aTZ);
	}

	private PersonIdent(final String aName, final String aEmailAddress,
						long when) {
		this(aName, aEmailAddress, when, SystemReader.getInstance()
				.getTimezone(when));
	}

	private PersonIdent(UserConfig config) {
		this(config.getCommitterName(), config.getCommitterEmail());
	}

	public PersonIdent(final String aName, final String aEmailAddress,
					   final long aWhen, final int aTZ) {
		if(aName == null)
			throw new IllegalArgumentException(
					JGitText.get().personIdentNameNonNull);
		if(aEmailAddress == null)
			throw new IllegalArgumentException(
					JGitText.get().personIdentEmailNonNull);
		name = aName;
		emailAddress = aEmailAddress;
		when = aWhen;
		tzOffset = aTZ;
	}

	public String getName() {
		return name;
	}

	public String getEmailAddress() {
		return emailAddress;
	}

	public Date getWhen() {
		return new Date(when);
	}

	public TimeZone getTimeZone() {
		return getTimeZone(tzOffset);
	}

	public int getTimeZoneOffset() {
		return tzOffset;
	}

	@Override
	public int hashCode() {
		int hc = getEmailAddress().hashCode();
		hc *= 31;
		hc += (int) (when / 1000L);
		return hc;
	}

	@Override
	public boolean equals(Object o) {
		if(o instanceof PersonIdent) {
			final PersonIdent p = (PersonIdent) o;
			return getName().equals(p.getName())
					&& getEmailAddress().equals(p.getEmailAddress())
					&& when / 1000L == p.when / 1000L;
		}
		return false;
	}

	public String toExternalString() {
		final StringBuilder r = new StringBuilder();
		appendSanitized(r, getName());
		r.append(" <");
		appendSanitized(r, getEmailAddress());
		r.append("> ");
		r.append(when / 1000);
		r.append(' ');
		appendTimezone(r, tzOffset);
		return r.toString();
	}

	@Override
	@SuppressWarnings("nls")
	public String toString() {
		final StringBuilder r = new StringBuilder();
		final SimpleDateFormat dtfmt;
		dtfmt = new SimpleDateFormat("EEE MMM d HH:mm:ss yyyy Z", Locale.US);
		dtfmt.setTimeZone(getTimeZone());

		r.append("PersonIdent[");
		r.append(getName());
		r.append(", ");
		r.append(getEmailAddress());
		r.append(", ");
		r.append(dtfmt.format(when));
		r.append("]");

		return r.toString();
	}
}

