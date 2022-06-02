/*
 * Copyright (C) 2008, 2010 Google Inc.
 * Copyright (C) 2008, 2009 Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2008, 2020 Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.OutputStream;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.util.RawParseUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PacketLineOut {

	private static final Logger log = LoggerFactory.getLogger(PacketLineOut.class);
	private final OutputStream out;
	private final byte[] lenbuffer;
	private final boolean logEnabled;
	private boolean flushOnEnd;
	private boolean usingSideband;

	public PacketLineOut(OutputStream outputStream) {
		this(outputStream, true);
	}

	public PacketLineOut(OutputStream outputStream, boolean enableLogging) {
		out = outputStream;
		lenbuffer = new byte[5];
		flushOnEnd = true;
		logEnabled = enableLogging;
	}

	public void setFlushOnEnd(boolean flushOnEnd) {
		this.flushOnEnd = flushOnEnd;
	}

	public boolean isUsingSideband() {
		return usingSideband;
	}

	public void setUsingSideband(boolean value) {
		this.usingSideband = value;
	}

	public void writeString(String s) throws IOException {
		writePacket(Constants.encode(s));
	}

	public void writePacket(byte[] packet) throws IOException {
		writePacket(packet, 0, packet.length);
	}

	public void writePacket(byte[] buf, int pos, int len) throws IOException {
		if(usingSideband) {
			formatLength(len + 5);
			out.write(lenbuffer, 0, 4);
			out.write(1);
		} else {
			formatLength(len + 4);
			out.write(lenbuffer, 0, 4);
		}
		out.write(buf, pos, len);
		if(logEnabled && log.isDebugEnabled()) {
			if(len > 0 && buf[pos + len - 1] == '\n') {
				log.debug(
						"git> " + RawParseUtils.decode(UTF_8, buf, pos, len - 1)
								+ "\\n");
			} else {
				log.debug("git> " + RawParseUtils.decode(UTF_8, buf, pos, len));
			}
		}
	}

	public void writeDelim() throws IOException {
		formatLength(1);
		out.write(lenbuffer, 0, 4);
		if(logEnabled && log.isDebugEnabled()) {
			log.debug("git> 0001");
		}
	}

	public void end() throws IOException {
		formatLength(0);
		out.write(lenbuffer, 0, 4);
		if(logEnabled && log.isDebugEnabled()) {
			log.debug("git> 0000");
		}
		if(flushOnEnd) {
			flush();
		}
	}

	public void flush() throws IOException {
		out.flush();
	}

	private static final byte[] hexchar = {'0', '1', '2', '3', '4', '5', '6',
			'7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};

	private void formatLength(int w) {
		formatLength(lenbuffer, w);
	}

	static void formatLength(byte[] lenbuffer, int w) {
		int o = 3;
		while(o >= 0 && w != 0) {
			lenbuffer[o--] = hexchar[w & 0xf];
			w >>>= 4;
		}
		while(o >= 0)
			lenbuffer[o--] = '0';
	}
}
