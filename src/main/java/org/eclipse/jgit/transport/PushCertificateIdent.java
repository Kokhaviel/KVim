/*
 * Copyright (C) 2015, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.jgit.util.RawParseUtils.lastIndexOfTrim;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.util.MutableInteger;
import org.eclipse.jgit.util.RawParseUtils;

public class PushCertificateIdent {

	public static PushCertificateIdent parse(String str) {
		MutableInteger p = new MutableInteger();
		byte[] raw = str.getBytes(UTF_8);
		int tzBegin = raw.length - 1;
		tzBegin = lastIndexOfTrim(raw, ' ', tzBegin);
		if (tzBegin < 0 || raw[tzBegin] != ' ') {
			return new PushCertificateIdent(str, str, 0, 0);
		}
		int whenBegin = tzBegin++;
		int tz = RawParseUtils.parseTimeZoneOffset(raw, tzBegin, p);
		boolean hasTz = p.value != tzBegin;

		whenBegin = lastIndexOfTrim(raw, ' ', whenBegin);
		if (whenBegin < 0 || raw[whenBegin] != ' ') {
			return new PushCertificateIdent(str, str, 0, 0);
		}
		whenBegin++;
		int idEnd;
		long when = RawParseUtils.parseLongBase10(raw, whenBegin, p);
		boolean hasWhen = p.value != whenBegin;

		if (hasTz && hasWhen) {
			idEnd = whenBegin - 1;
		} else {
			tz = 0;
			when = 0;
			if (hasTz) {
				idEnd = tzBegin - 1;
			} else {
				idEnd = raw.length;
			}
		}
		String id = new String(raw, 0, idEnd, UTF_8);

		return new PushCertificateIdent(str, id, when * 1000L, tz);
	}

	private final String raw;
	private final String userId;
	private final long when;
	private final int tzOffset;

	private PushCertificateIdent(String raw, String userId, long when,
			int tzOffset) {
		this.raw = raw;
		this.userId = userId;
		this.when = when;
		this.tzOffset = tzOffset;
	}

	public String getRaw() {
		return raw;
	}

	public String getName() {
		int nameEnd = userId.indexOf('<');
		if (nameEnd < 0 || userId.indexOf('>', nameEnd) < 0) {
			nameEnd = userId.length();
		}
		nameEnd--;
		while (nameEnd >= 0 && userId.charAt(nameEnd) == ' ') {
			nameEnd--;
		}
		int nameBegin = 0;
		while (nameBegin < nameEnd && userId.charAt(nameBegin) == ' ') {
			nameBegin++;
		}
		return userId.substring(nameBegin, nameEnd + 1);
	}

	public Date getWhen() {
		return new Date(when);
	}

	public TimeZone getTimeZone() {
		return PersonIdent.getTimeZone(tzOffset);
	}

	@Override
	public boolean equals(Object o) {
		return (o instanceof PushCertificateIdent)
			&& raw.equals(((PushCertificateIdent) o).raw);
	}

	@Override
	public int hashCode() {
		return raw.hashCode();
	}

	@SuppressWarnings("nls")
	@Override
	public String toString() {
		SimpleDateFormat fmt;
		fmt = new SimpleDateFormat("EEE MMM d HH:mm:ss yyyy Z", Locale.US);
		fmt.setTimeZone(getTimeZone());
		return getClass().getSimpleName()
			+ "[raw=\"" + raw + "\","
			+ " userId=\"" + userId + "\","
			+ " " + fmt.format(when) + "]";
	}
}
