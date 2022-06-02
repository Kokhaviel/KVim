/*
 * Copyright (C) 2015, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import static org.eclipse.jgit.transport.ReceivePack.parseCommand;
import static org.eclipse.jgit.transport.GitProtocolConstants.CAPABILITY_PUSH_CERT;

import java.io.EOFException;
import java.io.IOException;
import java.io.Reader;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jgit.errors.PackProtocolException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PushCertificate.NonceStatus;
import org.eclipse.jgit.util.IO;

public class PushCertificateParser {
	static final String BEGIN_SIGNATURE =
			"-----BEGIN PGP SIGNATURE-----";
	static final String END_SIGNATURE =
			"-----END PGP SIGNATURE-----";

	static final String VERSION = "certificate version";

	static final String PUSHER = "pusher";

	static final String PUSHEE = "pushee";

	static final String NONCE = "nonce";

	static final String END_CERT = "push-cert-end";

	private static final String VERSION_0_1 = "0.1";

	private interface StringReader {
		String read() throws IOException;
	}

	private static class PacketLineReader implements StringReader {
		private final PacketLineIn pckIn;

		private PacketLineReader(PacketLineIn pckIn) {
			this.pckIn = pckIn;
		}

		@Override
		public String read() throws IOException {
			return pckIn.readString();
		}
	}

	private static class StreamReader implements StringReader {
		private final Reader reader;

		private StreamReader(Reader reader) {
			this.reader = reader;
		}

		@Override
		public String read() throws IOException {
			String line = IO.readLine(reader, 41 * 2 + 64);
			if(line.isEmpty()) {
				throw new EOFException();
			} else if(line.charAt(line.length() - 1) == '\n') {
				line = line.substring(0, line.length() - 1);
			}
			return line;
		}
	}

	public static PushCertificate fromReader(Reader r)
			throws IOException {
		return new PushCertificateParser().parse(r);
	}

	public static PushCertificate fromString(String str)
			throws IOException {
		return fromReader(new java.io.StringReader(str));
	}

	private boolean received;
	private String version;
	private PushCertificateIdent pusher;
	private String pushee;
	private String sentNonce;
	private String receivedNonce;
	private NonceStatus nonceStatus;
	private String signature;
	private final Repository db;
	private final int nonceSlopLimit;
	private final boolean enabled;
	private final NonceGenerator nonceGenerator;
	private final List<ReceiveCommand> commands = new ArrayList<>();

	public PushCertificateParser(Repository into, SignedPushConfig cfg) {
		if(cfg != null) {
			nonceSlopLimit = cfg.getCertNonceSlopLimit();
			nonceGenerator = cfg.getNonceGenerator();
		} else {
			nonceSlopLimit = 0;
			nonceGenerator = null;
		}
		db = into;
		enabled = nonceGenerator != null;
	}

	private PushCertificateParser() {
		db = null;
		nonceSlopLimit = 0;
		nonceGenerator = null;
		enabled = true;
	}

	public PushCertificate parse(Reader r)
			throws IOException {
		StreamReader reader = new StreamReader(r);
		receiveHeader(reader, true);
		String line;
		try {
			while(!(line = reader.read()).isEmpty()) {
				if(line.equals(BEGIN_SIGNATURE)) {
					receiveSignature(reader);
					break;
				}
				addCommand(line);
			}
		} catch(EOFException ignored) {
		}
		return build();
	}

	public PushCertificate build() throws IOException {
		if(!received || !enabled) {
			return null;
		}
		try {
			return new PushCertificate(version, pusher, pushee, receivedNonce,
					nonceStatus, Collections.unmodifiableList(commands), signature);
		} catch(IllegalArgumentException e) {
			throw new IOException(e.getMessage(), e);
		}
	}

	public boolean enabled() {
		return enabled;
	}

	public String getAdvertiseNonce() {
		String nonce = sentNonce();
		if(nonce == null) {
			return null;
		}
		return CAPABILITY_PUSH_CERT + '=' + nonce;
	}

	private String sentNonce() {
		if(sentNonce == null && nonceGenerator != null) {
			sentNonce = nonceGenerator.createNonce(db,
					TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()));
		}
		return sentNonce;
	}

	private static String parseHeader(StringReader reader, String header)
			throws IOException {
		return parseHeader(reader.read(), header);
	}

	private static String parseHeader(String s, String header)
			throws IOException {
		if(s.isEmpty()) {
			throw new EOFException();
		}
		if(s.length() <= header.length()
				|| !s.startsWith(header)
				|| s.charAt(header.length()) != ' ') {
			throw new PackProtocolException(MessageFormat.format(
					JGitText.get().pushCertificateInvalidField, header));
		}
		return s.substring(header.length() + 1);
	}

	public void receiveHeader(PacketLineIn pckIn, boolean stateless)
			throws IOException {
		receiveHeader(new PacketLineReader(pckIn), stateless);
	}

	private void receiveHeader(StringReader reader, boolean stateless)
			throws IOException {
		try {
			try {
				version = parseHeader(reader, VERSION);
			} catch(EOFException e) {
				return;
			}
			received = true;
			if(!version.equals(VERSION_0_1)) {
				throw new PackProtocolException(MessageFormat.format(
						JGitText.get().pushCertificateInvalidFieldValue, VERSION, version));
			}
			String rawPusher = parseHeader(reader, PUSHER);
			pusher = PushCertificateIdent.parse(rawPusher);
			String next = reader.read();
			if(next.startsWith(PUSHEE)) {
				pushee = parseHeader(next, PUSHEE);
				receivedNonce = parseHeader(reader, NONCE);
			} else {
				receivedNonce = parseHeader(next, NONCE);
			}
			nonceStatus = nonceGenerator != null
					? nonceGenerator.verify(
					receivedNonce, sentNonce(), db, stateless, nonceSlopLimit)
					: NonceStatus.UNSOLICITED;
			if(!reader.read().isEmpty()) {
				throw new PackProtocolException(
						JGitText.get().pushCertificateInvalidHeader);
			}
		} catch(EOFException eof) {
			throw new PackProtocolException(
					JGitText.get().pushCertificateInvalidHeader, eof);
		}
	}

	public void receiveSignature(PacketLineIn pckIn) throws IOException {
		StringReader reader = new PacketLineReader(pckIn);
		receiveSignature(reader);
		if(!reader.read().equals(END_CERT)) {
			throw new PackProtocolException(
					JGitText.get().pushCertificateInvalidSignature);
		}
	}

	private void receiveSignature(StringReader reader) throws IOException {
		received = true;
		try {
			StringBuilder sig = new StringBuilder(BEGIN_SIGNATURE).append('\n');
			String line;
			while(!(line = reader.read()).equals(END_SIGNATURE)) {
				sig.append(line).append('\n');
			}
			signature = sig.append(END_SIGNATURE).append('\n').toString();
		} catch(EOFException eof) {
			throw new PackProtocolException(
					JGitText.get().pushCertificateInvalidSignature, eof);
		}
	}

	public void addCommand(ReceiveCommand cmd) {
		commands.add(cmd);
	}

	public void addCommand(String line) throws PackProtocolException {
		commands.add(parseCommand(line));
	}
}
