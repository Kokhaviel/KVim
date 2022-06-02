/*
 * Copyright (C) 2015 Obeo. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.api.errors;

import java.text.MessageFormat;

import org.eclipse.jgit.internal.JGitText;

public class AbortedByHookException extends GitAPIException {
	private static final long serialVersionUID = 1L;

	public AbortedByHookException(String hookStdErr, String hookName) {
		super(MessageFormat.format(JGitText.get().commandRejectedByHook, hookName, hookStdErr));
	}

}
