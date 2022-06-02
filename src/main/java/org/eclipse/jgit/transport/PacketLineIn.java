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
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.text.MessageFormat;
import java.util.Iterator;

import org.eclipse.jgit.errors.PackProtocolException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.MutableObjectId;
import org.eclipse.jgit.util.IO;
import org.eclipse.jgit.util.RawParseUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PacketLineIn {
	private static final Logger log = LoggerFactory.getLogger(PacketLineIn.class);

	public static final String END = "";
	public static final String DELIM = "";

	enum AckNackResult {
		NAK,
		ACK,
		ACK_CONTINUE,
		ACK_COMMON,
		ACK_READY
	}

	private final byte[] lineBuffer = new byte[SideBandOutputStream.SMALL_BUF];
	private final InputStream in;
	private long limit;

	public PacketLineIn(InputStream in) {
		this(in, 0);
	}

	public PacketLineIn(InputStream in, long limit) {
		this.in = in;
		this.limit = limit;
	}

	static AckNackResult parseACKv2(String line, MutableObjectId returnedId)
			throws IOException {
		if("NAK".equals(line)) {
			return AckNackResult.NAK;
		}
		if(line.startsWith("ACK ") && line.length() == 44) {
			returnedId.fromString(line.substring(4, 44));
			return AckNackResult.ACK_COMMON;
		}
		if("ready".equals(line)) {
			return AckNackResult.ACK_READY;
		}
		if(line.startsWith("ERR ")) {
			throw new PackProtocolException(line.substring(4));
		}
		throw new PackProtocolException(
				MessageFormat.format(JGitText.get().expectedACKNAKGot, line));
	}

	AckNackResult readACK(MutableObjectId returnedId) throws IOException {
		final String line = readString();
		if(line.length() == 0)
			throw new PackProtocolException(JGitText.get().expectedACKNAKFoundEOF);
		if("NAK".equals(line))
			return AckNackResult.NAK;
		if(line.startsWith("ACK ")) {
			returnedId.fromString(line.substring(4, 44));
			if(line.length() == 44)
				return AckNackResult.ACK;

			final String arg = line.substring(44);
			switch(arg) {
				case " continue":
					return AckNackResult.ACK_CONTINUE;
				case " common":
					return AckNackResult.ACK_COMMON;
				case " ready":
					return AckNackResult.ACK_READY;
				default:
					break;
			}
		}
		if(line.startsWith("ERR "))
			throw new PackProtocolException(line.substring(4));
		throw new PackProtocolException(MessageFormat.format(JGitText.get().expectedACKNAKGot, line));
	}

	public String readString() throws IOException {
		int len = readLength();
		if(len == 0) {
			log.debug("git< 0000");
			return END;
		}
		if(len == 1) {
			log.debug("git< 0001");
			return DELIM;
		}

		len -= 4;
		if(len == 0) {
			log.debug("git< ");
			return "";
		}

		byte[] raw;
		if(len <= lineBuffer.length)
			raw = lineBuffer;
		else
			raw = new byte[len];

		IO.readFully(in, raw, 0, len);
		if(raw[len - 1] == '\n')
			len--;

		String s = RawParseUtils.decode(UTF_8, raw, 0, len);
		log.debug("git< " + s);
		return s;
	}

	public PacketLineInIterator readStrings() throws IOException {
		return new PacketLineInIterator(this);
	}

	public String readStringRaw() throws IOException {
		int len = readLength();
		if(len == 0) {
			log.debug("git< 0000");
			return END;
		}

		len -= 4;

		byte[] raw;
		if(len <= lineBuffer.length)
			raw = lineBuffer;
		else
			raw = new byte[len];

		IO.readFully(in, raw, 0, len);

		String s = RawParseUtils.decode(UTF_8, raw, 0, len);
		log.debug("git< " + s);
		return s;
	}

	@SuppressWarnings({"ReferenceEquality", "StringEquality"})
	public static boolean isDelimiter(String s) {
		return s == DELIM;
	}

	@SuppressWarnings({"ReferenceEquality", "StringEquality"})
	public static boolean isEnd(String s) {
		return s == END;
	}

	void discardUntilEnd() throws IOException {
		for(; ; ) {
			int n = readLength();
			if(n == 0) {
				break;
			}
			IO.skipFully(in, n - 4);
		}
	}

	int readLength() throws IOException {
		IO.readFully(in, lineBuffer, 0, 4);
		int len;
		try {
			len = RawParseUtils.parseHexInt16(lineBuffer, 0);
		} catch(ArrayIndexOutOfBoundsException err) {
			throw invalidHeader(err);
		}

		if(len == 0) {
			return 0;
		} else if(len == 1) {
			return 1;
		} else if(len < 4) {
			throw invalidHeader();
		}

		if(limit != 0) {
			int n = len - 4;
			if(limit < n) {
				limit = -1;
				try {
					IO.skipFully(in, n);
				} catch(IOException ignored) {
				}
				throw new InputOverLimitIOException();
			}
			limit = n < limit ? limit - n : -1;
		}
		return len;
	}

	private IOException invalidHeader() {
		return new IOException(MessageFormat.format(JGitText.get().invalidPacketLineHeader,
				"" + (char) lineBuffer[0] + (char) lineBuffer[1]
						+ (char) lineBuffer[2] + (char) lineBuffer[3]));
	}

	private IOException invalidHeader(Throwable cause) {
		IOException ioe = invalidHeader();
		ioe.initCause(cause);
		return ioe;
	}

	public static class InputOverLimitIOException extends IOException {
		private static final long serialVersionUID = 1L;
	}

	public static class PacketLineInIterator implements Iterable<String> {
		private final PacketLineIn in;

		private String current;

		PacketLineInIterator(PacketLineIn in) throws IOException {
			this.in = in;
			current = in.readString();
		}

		@Override
		public Iterator<String> iterator() {
			return new Iterator<String>() {

				@Override
				public boolean hasNext() {
					return !PacketLineIn.isEnd(current);
				}

				@Override
				public String next() {
					String next = current;
					try {
						current = in.readString();
					} catch(IOException e) {
						throw new UncheckedIOException(e);
					}
					return next;
				}
			};
		}

	}
}
