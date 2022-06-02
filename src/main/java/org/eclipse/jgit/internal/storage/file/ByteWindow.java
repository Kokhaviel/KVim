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

package org.eclipse.jgit.internal.storage.file;

import java.io.IOException;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import org.eclipse.jgit.internal.storage.pack.PackOutputStream;

abstract class ByteWindow {
	protected final Pack pack;
	protected final long start;
	protected final long end;

	protected ByteWindow(Pack p, long s, int n) {
		pack = p;
		start = s;
		end = start + n;
	}

	final int size() {
		return (int) (end - start);
	}

	final boolean contains(Pack neededPack, long neededPos) {
		return pack == neededPack && start <= neededPos && neededPos < end;
	}

	final int copy(long pos, byte[] dstbuf, int dstoff, int cnt) {
		return copy((int) (pos - start), dstbuf, dstoff, cnt);
	}

	protected abstract int copy(int pos, byte[] dstbuf, int dstoff, int cnt);

	abstract void write(PackOutputStream out, long pos, int cnt) throws IOException;

	final int setInput(long pos, Inflater inf) throws DataFormatException {
		return setInput((int) (pos - start), inf);
	}

	protected abstract int setInput(int pos, Inflater inf) throws DataFormatException;
}
