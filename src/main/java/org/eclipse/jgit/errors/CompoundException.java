/*
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.errors;

import org.eclipse.jgit.internal.JGitText;

import java.util.Collection;

public class CompoundException extends Exception {
	private static final long serialVersionUID = 1L;

	private static String format(Collection<Throwable> causes) {
		final StringBuilder msg = new StringBuilder();
		msg.append(JGitText.get().failureDueToOneOfTheFollowing);
		for (Throwable c : causes) {
			msg.append("  ");
			msg.append(c.getMessage());
			msg.append("\n");
		}
		return msg.toString();
	}

	public CompoundException(Collection<Throwable> why) {
		super(format(why));
	}

}
