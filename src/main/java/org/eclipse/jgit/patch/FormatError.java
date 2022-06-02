/*
 * Copyright (C) 2008, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.patch;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.Locale;

import org.eclipse.jgit.util.RawParseUtils;

public class FormatError {

	public enum Severity {
		WARNING,
		ERROR
	}

	private final byte[] buf;
	private final int offset;
	private final Severity severity;
	private final String message;

	FormatError(final byte[] buffer, final int ptr, final Severity sev,
				final String msg) {
		buf = buffer;
		offset = ptr;
		severity = sev;
		message = msg;
	}

	public Severity getSeverity() {
		return severity;
	}

	public String getMessage() {
		return message;
	}

	public byte[] getBuffer() {
		return buf;
	}

	public int getOffset() {
		return offset;
	}

	public String getLineText() {
		final int eol = RawParseUtils.nextLF(buf, offset);
		return RawParseUtils.decode(UTF_8, buf, offset, eol);
	}

	@Override
	public String toString() {
		return getSeverity().name().toLowerCase(Locale.ROOT) +
				": at offset " +
				getOffset() +
				": " +
				getMessage() +
				"\n" +
				"  in " +
				getLineText();
	}
}
