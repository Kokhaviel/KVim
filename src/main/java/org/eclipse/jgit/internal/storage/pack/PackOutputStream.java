/*
 * Copyright (C) 2008-2009, Google Inc.
 * Copyright (C) 2008, Marek Zawirski <marek.zawirski@gmail.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.pack;

import static org.eclipse.jgit.lib.Constants.OBJ_OFS_DELTA;
import static org.eclipse.jgit.lib.Constants.OBJ_REF_DELTA;
import static org.eclipse.jgit.lib.Constants.PACK_SIGNATURE;

import java.io.IOException;
import java.io.OutputStream;

import org.eclipse.jgit.internal.storage.io.CancellableDigestOutputStream;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.util.NB;

public final class PackOutputStream extends CancellableDigestOutputStream {

	private final PackWriter packWriter;
	private final byte[] headerBuffer = new byte[32];
	private final byte[] copyBuffer = new byte[64 << 10];
	private boolean ofsDelta;

	public PackOutputStream(final ProgressMonitor writeMonitor,
							final OutputStream out, final PackWriter pw) {
		super(writeMonitor, out);
		this.packWriter = pw;
	}

	void writeFileHeader(int version, long objectCount)
			throws IOException {
		System.arraycopy(PACK_SIGNATURE, 0, headerBuffer, 0, 4);
		NB.encodeInt32(headerBuffer, 4, version);
		NB.encodeInt32(headerBuffer, 8, (int) objectCount);
		write(headerBuffer, 0, 12);
		ofsDelta = packWriter.isDeltaBaseAsOffset();
	}

	public void writeObject(ObjectToPack otp) throws IOException {
		packWriter.writeObject(this, otp);
	}

	public void writeHeader(ObjectToPack otp, long rawLength)
			throws IOException {
		ObjectToPack b = otp.getDeltaBase();
		if(b != null && (b.isWritten() & ofsDelta)) {
			int n = objectHeader(rawLength, OBJ_OFS_DELTA, headerBuffer);
			n = ofsDelta(length() - b.getOffset(), headerBuffer, n);
			write(headerBuffer, 0, n);
		} else if(otp.isDeltaRepresentation()) {
			int n = objectHeader(rawLength, OBJ_REF_DELTA, headerBuffer);
			otp.getDeltaBaseId().copyRawTo(headerBuffer, n);
			write(headerBuffer, 0, n + 20);
		} else {
			int n = objectHeader(rawLength, otp.getType(), headerBuffer);
			write(headerBuffer, 0, n);
		}
	}

	private static int objectHeader(long len, int type, byte[] buf) {
		byte b = (byte) ((type << 4) | (len & 0x0F));
		int n = 0;
		for(len >>>= 4; len != 0; len >>>= 7) {
			buf[n++] = (byte) (0x80 | b);
			b = (byte) (len & 0x7F);
		}
		buf[n++] = b;
		return n;
	}

	private static int ofsDelta(long diff, byte[] buf, int p) {
		p += ofsDeltaVarIntLength(diff);
		int n = p;
		buf[--n] = (byte) (diff & 0x7F);
		while((diff >>>= 7) != 0)
			buf[--n] = (byte) (0x80 | (--diff & 0x7F));
		return p;
	}

	private static int ofsDeltaVarIntLength(long v) {
		int n = 1;
		for(; (v >>>= 7) != 0; n++)
			--v;
		return n;
	}

	public byte[] getCopyBuffer() {
		return copyBuffer;
	}

	void endObject() {
		getWriteMonitor().update(1);
	}
}
