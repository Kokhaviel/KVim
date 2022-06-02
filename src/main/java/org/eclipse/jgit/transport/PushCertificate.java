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

import static org.eclipse.jgit.transport.PushCertificateParser.NONCE;
import static org.eclipse.jgit.transport.PushCertificateParser.PUSHEE;
import static org.eclipse.jgit.transport.PushCertificateParser.PUSHER;
import static org.eclipse.jgit.transport.PushCertificateParser.VERSION;

import java.text.MessageFormat;
import java.util.List;
import java.util.Objects;

import org.eclipse.jgit.internal.JGitText;

public class PushCertificate {

	public enum NonceStatus {
		UNSOLICITED,
		BAD,
		MISSING,
		OK,
		SLOP
	}

	private final String version;
	private final PushCertificateIdent pusher;
	private final String pushee;
	private final String nonce;
	private final NonceStatus nonceStatus;
	private final List<ReceiveCommand> commands;
	private final String signature;

	PushCertificate(String version, PushCertificateIdent pusher, String pushee,
					String nonce, NonceStatus nonceStatus, List<ReceiveCommand> commands,
					String signature) {
		if(version == null || version.isEmpty()) {
			throw new IllegalArgumentException(MessageFormat.format(
					JGitText.get().pushCertificateInvalidField, VERSION));
		}
		if(pusher == null) {
			throw new IllegalArgumentException(MessageFormat.format(
					JGitText.get().pushCertificateInvalidField, PUSHER));
		}
		if(nonce == null || nonce.isEmpty()) {
			throw new IllegalArgumentException(MessageFormat.format(
					JGitText.get().pushCertificateInvalidField, NONCE));
		}
		if(nonceStatus == null) {
			throw new IllegalArgumentException(MessageFormat.format(
					JGitText.get().pushCertificateInvalidField,
					"nonce status"));
		}
		if(commands == null || commands.isEmpty()) {
			throw new IllegalArgumentException(MessageFormat.format(
					JGitText.get().pushCertificateInvalidField,
					"command"));
		}
		if(signature == null || signature.isEmpty()) {
			throw new IllegalArgumentException(
					JGitText.get().pushCertificateInvalidSignature);
		}
		if(!signature.startsWith(PushCertificateParser.BEGIN_SIGNATURE)
				|| !signature.endsWith(PushCertificateParser.END_SIGNATURE + '\n')) {
			throw new IllegalArgumentException(
					JGitText.get().pushCertificateInvalidSignature);
		}
		this.version = version;
		this.pusher = pusher;
		this.pushee = pushee;
		this.nonce = nonce;
		this.nonceStatus = nonceStatus;
		this.commands = commands;
		this.signature = signature;
	}

	public String getVersion() {
		return version;
	}

	public String getPusher() {
		return pusher.getRaw();
	}

	public List<ReceiveCommand> getCommands() {
		return commands;
	}

	public String toTextWithSignature() {
		return toStringBuilder().append(signature).toString();
	}

	private StringBuilder toStringBuilder() {
		StringBuilder sb = new StringBuilder()
				.append(VERSION).append(' ').append(version).append('\n')
				.append(PUSHER).append(' ').append(getPusher())
				.append('\n');
		if(pushee != null) {
			sb.append(PUSHEE).append(' ').append(pushee).append('\n');
		}
		sb.append(NONCE).append(' ').append(nonce).append('\n')
				.append('\n');
		for(ReceiveCommand cmd : commands) {
			sb.append(cmd.getOldId().name())
					.append(' ').append(cmd.getNewId().name())
					.append(' ').append(cmd.getRefName()).append('\n');
		}
		return sb;
	}

	@Override
	public int hashCode() {
		return signature.hashCode();
	}

	@Override
	public boolean equals(Object o) {
		if(!(o instanceof PushCertificate)) {
			return false;
		}
		PushCertificate p = (PushCertificate) o;
		return version.equals(p.version)
				&& pusher.equals(p.pusher)
				&& Objects.equals(pushee, p.pushee)
				&& nonceStatus == p.nonceStatus
				&& signature.equals(p.signature)
				&& commandsEqual(this, p);
	}

	private static boolean commandsEqual(PushCertificate c1, PushCertificate c2) {
		if(c1.commands.size() != c2.commands.size()) {
			return false;
		}
		for(int i = 0; i < c1.commands.size(); i++) {
			ReceiveCommand cmd1 = c1.commands.get(i);
			ReceiveCommand cmd2 = c2.commands.get(i);
			if(!cmd1.getOldId().equals(cmd2.getOldId())
					|| !cmd1.getNewId().equals(cmd2.getNewId())
					|| !cmd1.getRefName().equals(cmd2.getRefName())) {
				return false;
			}
		}
		return true;
	}

	@Override
	public String toString() {
		return getClass().getSimpleName() + '['
				+ toTextWithSignature() + ']';
	}
}
