/*
 * Copyright (C) 2017, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.util.sha1;

import java.text.MessageFormat;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.ObjectId;

public class Sha1CollisionException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	public Sha1CollisionException(ObjectId id) {
		super(MessageFormat.format(JGitText.get().sha1CollisionDetected, id.name()));
	}
}
