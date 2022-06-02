/*
 * Copyright (C) 2009, Jonas Fonseca <fonseca@diku.dk>
 * Copyright (C) 2007, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.errors;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.text.MessageFormat;

import org.eclipse.jgit.internal.JGitText;

public class InvalidObjectIdException extends IllegalArgumentException {
	private static final long serialVersionUID = 1L;

	public InvalidObjectIdException(byte[] bytes, int offset, int length) {
		super(msg(bytes, offset, length));
	}

	public InvalidObjectIdException(String id) {
		super(MessageFormat.format(JGitText.get().invalidId, id));
	}

	private static String msg(byte[] bytes, int offset, int length) {
		try {
			return MessageFormat.format(
					JGitText.get().invalidId,
					new String(bytes, offset, length, US_ASCII));
		} catch (StringIndexOutOfBoundsException e) {
			return JGitText.get().invalidId0;
		}
	}
}
