/*
 * Copyright (C) 2008, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.file;

import java.io.IOException;
import java.io.OutputStream;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.transport.PackedObjectInfo;
import org.eclipse.jgit.util.NB;

class PackIndexWriterV1 extends PackIndexWriter {
	static boolean canStore(PackedObjectInfo oe) {
		return oe.getOffset() >>> 1 < Integer.MAX_VALUE;
	}

	PackIndexWriterV1(final OutputStream dst) {
		super(dst);
	}

	@Override
	protected void writeImpl() throws IOException {
		writeFanOutTable();

		for(PackedObjectInfo oe : entries) {
			if(!canStore(oe))
				throw new IOException(JGitText.get().packTooLargeForIndexVersion1);
			NB.encodeInt32(tmp, 0, (int) oe.getOffset());
			oe.copyRawTo(tmp, 4);
			out.write(tmp);
		}

		writeChecksumFooter();
	}
}
