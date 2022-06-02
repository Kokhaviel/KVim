/*
 * Copyright (C) 2008, Florian Köberle <florianskarten@web.de>
 * Copyright (C) 2009, Vasyl' Vavrychuk <vvavrychuk@gmail.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.errors;

public class InvalidPatternException extends Exception {
	private static final long serialVersionUID = 1L;
	private final String pattern;

	public InvalidPatternException(String message, String pattern) {
		super(message);
		this.pattern = pattern;
	}

	public InvalidPatternException(String message, String pattern,
			Throwable cause) {
		this(message, pattern);
		initCause(cause);
	}

	public String getPattern() {
		return pattern;
	}

}
