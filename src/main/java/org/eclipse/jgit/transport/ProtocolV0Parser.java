/*
 * Copyright (C) 2018, Google LLC. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.transport;

import static org.eclipse.jgit.transport.GitProtocolConstants.OPTION_FILTER;

import java.io.EOFException;
import java.io.IOException;
import java.text.MessageFormat;

import org.eclipse.jgit.errors.PackProtocolException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.internal.transport.parser.FirstWant;
import org.eclipse.jgit.lib.ObjectId;

final class ProtocolV0Parser {

	private final TransferConfig transferConfig;

	ProtocolV0Parser(TransferConfig transferConfig) {
		this.transferConfig = transferConfig;
	}

	FetchV0Request recvWants(PacketLineIn pckIn)
			throws IOException {
		FetchV0Request.Builder reqBuilder = new FetchV0Request.Builder();

		boolean isFirst = true;
		boolean filterReceived = false;

		for(; ; ) {
			String line;
			try {
				line = pckIn.readString();
			} catch(EOFException eof) {
				if(isFirst) {
					break;
				}
				throw eof;
			}

			if(PacketLineIn.isEnd(line)) {
				break;
			}

			if(line.startsWith("deepen ")) {
				int depth = Integer.parseInt(line.substring(7));
				if(depth <= 0) {
					throw new PackProtocolException(
							MessageFormat.format(JGitText.get().invalidDepth,
									depth));
				}
				reqBuilder.setDepth(depth);
				continue;
			}

			if(line.startsWith("shallow ")) {
				reqBuilder.addClientShallowCommit(
						ObjectId.fromString(line.substring(8)));
				continue;
			}

			if(transferConfig.isAllowFilter()
					&& line.startsWith(OPTION_FILTER + " ")) {
				String arg = line.substring(OPTION_FILTER.length() + 1);

				if(filterReceived) {
					throw new PackProtocolException(
							JGitText.get().tooManyFilters);
				}
				filterReceived = true;

				reqBuilder.setFilterSpec(FilterSpec.fromFilterLine(arg));
				continue;
			}

			if(!line.startsWith("want ") || line.length() < 45) {
				throw new PackProtocolException(MessageFormat
						.format(JGitText.get().expectedGot, "want", line));
			}

			if(isFirst) {
				if(line.length() > 45) {
					FirstWant firstLine = FirstWant.fromLine(line);
					reqBuilder.addClientCapabilities(firstLine.getCapabilities());
					reqBuilder.setAgent(firstLine.getAgent());
					line = firstLine.getLine();
				}
			}

			reqBuilder.addWantId(ObjectId.fromString(line.substring(5)));
			isFirst = false;
		}

		return reqBuilder.build();
	}

}
