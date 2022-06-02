/*
 * Copyright (C) 2016, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import java.text.MessageFormat;

import org.eclipse.jgit.errors.PackProtocolException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.AnyObjectId;

public class WantNotValidException extends PackProtocolException {
	private static final long serialVersionUID = 1L;

	public WantNotValidException(AnyObjectId id) {
		super(msg(id));
	}

	public WantNotValidException(AnyObjectId id, Throwable cause) {
		super(msg(id), cause);
	}

	private static String msg(AnyObjectId id) {
		return MessageFormat.format(JGitText.get().wantNotValid, id.name());
	}
}
