/*
 * Copyright (C) 2008, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2006, Shawn O. Pearce <spearce@spearce.org>
 * Copyright (C) 2009, Vasyl' Vavrychuk <vvavrychuk@gmail.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.errors;


public class RevisionSyntaxException extends IllegalArgumentException {
	private static final long serialVersionUID = 1L;
	private final String revstr;

	public RevisionSyntaxException(String revstr) {
		this.revstr = revstr;
	}

	public RevisionSyntaxException(String message, String revstr) {
		super(message);
		this.revstr = revstr;
	}

	@Override
	public String toString() {
		return super.toString() + ":" + revstr;
	}
}
