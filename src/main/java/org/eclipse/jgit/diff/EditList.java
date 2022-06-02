/*
 * Copyright (C) 2009, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.diff;

import java.util.ArrayList;

public class EditList extends ArrayList<Edit> {
	private static final long serialVersionUID = 1L;

	public static EditList singleton(Edit edit) {
		EditList res = new EditList(1);
		res.add(edit);
		return res;
	}

	public EditList() {
		super(16);
	}

	public EditList(int capacity) {
		super(capacity);
	}

	@Override
	public String toString() {
		return "EditList" + super.toString();
	}
}
