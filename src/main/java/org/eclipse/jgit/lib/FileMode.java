/*
 * Copyright (C) 2007, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2006-2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.lib;

import java.io.IOException;
import java.io.OutputStream;

public abstract class FileMode {

	public static final int TYPE_MASK = 61440;
	public static final int TYPE_TREE = 16384;
	public static final int TYPE_SYMLINK = 40960;
	public static final int TYPE_FILE = 32768;
	public static final int TYPE_GITLINK = 57344;
	public static final int TYPE_MISSING = 0;

	public static final FileMode TREE = new FileMode(TYPE_TREE,
			Constants.OBJ_TREE) {
		@Override
		public boolean equals(int modeBits) {
			return (modeBits & TYPE_MASK) == TYPE_TREE;
		}
	};

	public static final FileMode SYMLINK = new FileMode(TYPE_SYMLINK,
			Constants.OBJ_BLOB) {
		@Override
		public boolean equals(int modeBits) {
			return (modeBits & TYPE_MASK) == TYPE_SYMLINK;
		}
	};

	public static final FileMode REGULAR_FILE = new FileMode(33188,
			Constants.OBJ_BLOB) {
		@Override
		public boolean equals(int modeBits) {
			return (modeBits & TYPE_MASK) == TYPE_FILE && (modeBits & 73) == 0;
		}
	};

	public static final FileMode EXECUTABLE_FILE = new FileMode(33261,
			Constants.OBJ_BLOB) {
		@Override
		public boolean equals(int modeBits) {
			return (modeBits & TYPE_MASK) == TYPE_FILE && (modeBits & 73) != 0;
		}
	};

	public static final FileMode GITLINK = new FileMode(TYPE_GITLINK,
			Constants.OBJ_COMMIT) {
		@Override
		public boolean equals(int modeBits) {
			return (modeBits & TYPE_MASK) == TYPE_GITLINK;
		}
	};

	public static final FileMode MISSING = new FileMode(TYPE_MISSING,
			Constants.OBJ_BAD) {
		@Override
		public boolean equals(int modeBits) {
			return modeBits == 0;
		}
	};

	public static FileMode fromBits(int bits) {
		switch(bits & TYPE_MASK) {
			case TYPE_MISSING:
				if(bits == 0)
					return MISSING;
				break;
			case TYPE_TREE:
				return TREE;
			case TYPE_FILE:
				if((bits & 73) != 0)
					return EXECUTABLE_FILE;
				return REGULAR_FILE;
			case TYPE_SYMLINK:
				return SYMLINK;
			case TYPE_GITLINK:
				return GITLINK;
		}

		return new FileMode(bits, Constants.OBJ_BAD) {
			@Override
			public boolean equals(int a) {
				return bits == a;
			}
		};
	}

	private final byte[] octalBytes;

	private final int modeBits;

	private final int objectType;

	private FileMode(int mode, int expType) {
		modeBits = mode;
		objectType = expType;
		if(mode != 0) {
			final byte[] tmp = new byte[10];
			int p = tmp.length;

			while(mode != 0) {
				tmp[--p] = (byte) ('0' + (mode & 7));
				mode >>= 3;
			}

			octalBytes = new byte[tmp.length - p];
			System.arraycopy(tmp, p, octalBytes, 0, octalBytes.length);
		} else {
			octalBytes = new byte[] {'0'};
		}
	}

	public abstract boolean equals(int modebits);

	public void copyTo(OutputStream os) throws IOException {
		os.write(octalBytes);
	}

	public void copyTo(byte[] buf, int ptr) {
		System.arraycopy(octalBytes, 0, buf, ptr, octalBytes.length);
	}

	public int copyToLength() {
		return octalBytes.length;
	}

	public int getObjectType() {
		return objectType;
	}

	@Override
	public String toString() {
		return Integer.toOctalString(modeBits);
	}

	public int getBits() {
		return modeBits;
	}
}
