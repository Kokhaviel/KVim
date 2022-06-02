/*
 * Copyright (C) 2010, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.pack;

final class DeltaWindowEntry {
	DeltaWindowEntry prev;
	DeltaWindowEntry next;
	ObjectToPack object;
	byte[] buffer;
	DeltaIndex index;

	void set(ObjectToPack object) {
		this.object = object;
		this.index = null;
		this.buffer = null;
	}

	int depth() {
		return object.getDeltaDepth();
	}

	int type() {
		return object.getType();
	}

	int size() {
		return object.getWeight();
	}

	boolean empty() {
		return object == null;
	}

	void makeNext(DeltaWindowEntry e) {
		e.prev.next = e.next;
		e.next.prev = e.prev;
		e.next = next;
		e.prev = this;
		next.prev = e;
		next = e;
	}

	static DeltaWindowEntry createWindow(int cnt) {
		DeltaWindowEntry res = new DeltaWindowEntry();
		DeltaWindowEntry p = res;
		for(int i = 0; i < cnt; i++) {
			DeltaWindowEntry e = new DeltaWindowEntry();
			e.prev = p;
			p.next = e;
			p = e;
		}
		p.next = res;
		res.prev = p;
		return res;
	}
}
