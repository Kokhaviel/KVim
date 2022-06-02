/*
 * Copyright (C) 2010, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.notes;

import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.ObjectId;

public class Note extends ObjectId {
	private ObjectId data;

	public Note(AnyObjectId noteOn, ObjectId noteData) {
		super(noteOn);
		data = noteData;
	}

	public ObjectId getData() {
		return data;
	}

	void setData(ObjectId newData) {
		data = newData;
	}

	@Override
	public String toString() {
		return "Note[" + name() + " -> " + data.name() + "]";
	}
}
