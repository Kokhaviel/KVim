/*
 * Copyright (C) 2010, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.errors;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.Collection;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.lib.ObjectId;

public class AmbiguousObjectException extends IOException {
	private static final long serialVersionUID = 1L;

	public AmbiguousObjectException(final AbbreviatedObjectId id) {
		super(MessageFormat.format(JGitText.get().ambiguousObjectAbbreviation, id.name()));
	}

}
