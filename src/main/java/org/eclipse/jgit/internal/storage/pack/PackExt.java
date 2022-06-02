/*
 * Copyright (C) 2013, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.pack;

public enum PackExt {
	PACK("pack"),
	INDEX("idx"),
	KEEP("keep"),
	BITMAP_INDEX("bitmap"),

	REFTABLE("ref");

	private final String ext;

	PackExt(String ext) {
		this.ext = ext;
	}

	public String getExtension() {
		return ext;
	}

	public int getPosition() {
		return ordinal();
	}

	public int getBit() {
		return 1 << getPosition();
	}

	@Override
	public String toString() {
		return String.format("PackExt[%s, bit=0x%s]", getExtension(),
				Integer.toHexString(getBit()));
	}
}
