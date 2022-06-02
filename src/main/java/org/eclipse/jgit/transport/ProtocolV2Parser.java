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

import static org.eclipse.jgit.transport.GitProtocolConstants.OPTION_DEEPEN_RELATIVE;
import static org.eclipse.jgit.transport.GitProtocolConstants.OPTION_FILTER;
import static org.eclipse.jgit.transport.GitProtocolConstants.OPTION_AGENT;
import static org.eclipse.jgit.transport.GitProtocolConstants.OPTION_INCLUDE_TAG;
import static org.eclipse.jgit.transport.GitProtocolConstants.OPTION_NO_PROGRESS;
import static org.eclipse.jgit.transport.GitProtocolConstants.OPTION_OFS_DELTA;
import static org.eclipse.jgit.transport.GitProtocolConstants.OPTION_SERVER_OPTION;
import static org.eclipse.jgit.transport.GitProtocolConstants.OPTION_SIDEBAND_ALL;
import static org.eclipse.jgit.transport.GitProtocolConstants.OPTION_SIDE_BAND_64K;
import static org.eclipse.jgit.transport.GitProtocolConstants.OPTION_THIN_PACK;
import static org.eclipse.jgit.transport.GitProtocolConstants.OPTION_WAIT_FOR_DONE;
import static org.eclipse.jgit.transport.GitProtocolConstants.OPTION_WANT_REF;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.eclipse.jgit.errors.InvalidObjectIdException;
import org.eclipse.jgit.errors.PackProtocolException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.ObjectId;

final class ProtocolV2Parser {

	private final TransferConfig transferConfig;

	ProtocolV2Parser(TransferConfig transferConfig) {
		this.transferConfig = transferConfig;
	}

	private static String consumeCapabilities(PacketLineIn pckIn,
											  Consumer<String> serverOptionConsumer,
											  Consumer<String> agentConsumer) throws IOException {

		String serverOptionPrefix = OPTION_SERVER_OPTION + '=';
		String agentPrefix = OPTION_AGENT + '=';

		String line = pckIn.readString();
		while(!PacketLineIn.isDelimiter(line) && !PacketLineIn.isEnd(line)) {
			if(line.startsWith(serverOptionPrefix)) {
				serverOptionConsumer
						.accept(line.substring(serverOptionPrefix.length()));
			} else if(line.startsWith(agentPrefix)) {
				agentConsumer.accept(line.substring(agentPrefix.length()));
			}

			line = pckIn.readString();
		}

		return line;
	}

	FetchV2Request parseFetchRequest(PacketLineIn pckIn)
			throws IOException {
		FetchV2Request.Builder reqBuilder = FetchV2Request.builder();

		reqBuilder.addClientCapability(OPTION_SIDE_BAND_64K);

		String line = consumeCapabilities(pckIn,
				reqBuilder::addServerOption,
				reqBuilder::setAgent);

		if(PacketLineIn.isEnd(line)) {
			return reqBuilder.build();
		}

		if(!PacketLineIn.isDelimiter(line)) {
			throw new PackProtocolException(
					MessageFormat.format(JGitText.get().unexpectedPacketLine,
							line));
		}

		boolean filterReceived = false;
		for(String line2 : pckIn.readStrings()) {
			if(line2.startsWith("want ")) {
				reqBuilder.addWantId(ObjectId.fromString(line2.substring(5)));
			} else if(transferConfig.isAllowRefInWant()
					&& line2.startsWith(OPTION_WANT_REF + " ")) {
				reqBuilder.addWantedRef(
						line2.substring(OPTION_WANT_REF.length() + 1));
			} else if(line2.startsWith("have ")) {
				reqBuilder.addPeerHas(ObjectId.fromString(line2.substring(5)));
			} else if(line2.equals("done")) {
				reqBuilder.setDoneReceived();
			} else if(line2.equals(OPTION_WAIT_FOR_DONE)) {
				reqBuilder.setWaitForDone();
			} else if(line2.equals(OPTION_THIN_PACK)) {
				reqBuilder.addClientCapability(OPTION_THIN_PACK);
			} else if(line2.equals(OPTION_NO_PROGRESS)) {
				reqBuilder.addClientCapability(OPTION_NO_PROGRESS);
			} else if(line2.equals(OPTION_INCLUDE_TAG)) {
				reqBuilder.addClientCapability(OPTION_INCLUDE_TAG);
			} else if(line2.equals(OPTION_OFS_DELTA)) {
				reqBuilder.addClientCapability(OPTION_OFS_DELTA);
			} else if(line2.startsWith("shallow ")) {
				reqBuilder.addClientShallowCommit(
						ObjectId.fromString(line2.substring(8)));
			} else if(line2.startsWith("deepen ")) {
				int parsedDepth = Integer.parseInt(line2.substring(7));
				if(parsedDepth <= 0) {
					throw new PackProtocolException(
							MessageFormat.format(JGitText.get().invalidDepth,
									parsedDepth));
				}
				if(reqBuilder.getDeepenSince() != 0) {
					throw new PackProtocolException(
							JGitText.get().deepenSinceWithDeepen);
				}
				if(reqBuilder.hasDeepenNotRefs()) {
					throw new PackProtocolException(
							JGitText.get().deepenNotWithDeepen);
				}
				reqBuilder.setDepth(parsedDepth);
			} else if(line2.startsWith("deepen-not ")) {
				reqBuilder.addDeepenNotRef(line2.substring(11));
				if(reqBuilder.getDepth() != 0) {
					throw new PackProtocolException(
							JGitText.get().deepenNotWithDeepen);
				}
			} else if(line2.equals(OPTION_DEEPEN_RELATIVE)) {
				reqBuilder.addClientCapability(OPTION_DEEPEN_RELATIVE);
			} else if(line2.startsWith("deepen-since ")) {
				int ts = Integer.parseInt(line2.substring(13));
				if(ts <= 0) {
					throw new PackProtocolException(MessageFormat
							.format(JGitText.get().invalidTimestamp, line2));
				}
				if(reqBuilder.getDepth() != 0) {
					throw new PackProtocolException(
							JGitText.get().deepenSinceWithDeepen);
				}
				reqBuilder.setDeepenSince(ts);
			} else if(transferConfig.isAllowFilter()
					&& line2.startsWith(OPTION_FILTER + ' ')) {
				if(filterReceived) {
					throw new PackProtocolException(
							JGitText.get().tooManyFilters);
				}
				filterReceived = true;
				reqBuilder.setFilterSpec(FilterSpec.fromFilterLine(
						line2.substring(OPTION_FILTER.length() + 1)));
			} else if(transferConfig.isAllowSidebandAll()
					&& line2.equals(OPTION_SIDEBAND_ALL)) {
				reqBuilder.setSidebandAll();
			} else if(line2.startsWith("packfile-uris ")) {
				for(String s : line2.substring(14).split(",")) {
					reqBuilder.addPackfileUriProtocol(s);
				}
			} else {
				throw new PackProtocolException(MessageFormat
						.format(JGitText.get().unexpectedPacketLine, line2));
			}
		}

		return reqBuilder.build();
	}

	LsRefsV2Request parseLsRefsRequest(PacketLineIn pckIn)
			throws IOException {
		LsRefsV2Request.Builder builder = LsRefsV2Request.builder();
		List<String> prefixes = new ArrayList<>();

		String line = consumeCapabilities(pckIn,
				builder::addServerOption,
				builder::setAgent);

		if(PacketLineIn.isEnd(line)) {
			return builder.build();
		}

		if(!PacketLineIn.isDelimiter(line)) {
			throw new PackProtocolException(MessageFormat
					.format(JGitText.get().unexpectedPacketLine, line));
		}

		for(String line2 : pckIn.readStrings()) {
			if(line2.equals("peel")) {
				builder.setPeel(true);
			} else if(line2.equals("symrefs")) {
				builder.setSymrefs(true);
			} else if(line2.startsWith("ref-prefix ")) {
				prefixes.add(line2.substring("ref-prefix ".length()));
			} else {
				throw new PackProtocolException(MessageFormat
						.format(JGitText.get().unexpectedPacketLine, line2));
			}
		}

		return builder.setRefPrefixes(prefixes).build();
	}

	ObjectInfoRequest parseObjectInfoRequest(PacketLineIn pckIn)
			throws IOException {
		ObjectInfoRequest.Builder builder = ObjectInfoRequest.builder();
		List<ObjectId> objectIDs = new ArrayList<>();

		String line = pckIn.readString();

		if(PacketLineIn.isEnd(line)) {
			return builder.build();
		}

		if(!line.equals("size")) {
			throw new PackProtocolException(MessageFormat
					.format(JGitText.get().unexpectedPacketLine, line));
		}

		for(String line2 : pckIn.readStrings()) {
			if(!line2.startsWith("oid ")) {
				throw new PackProtocolException(MessageFormat
						.format(JGitText.get().unexpectedPacketLine, line2));
			}

			String oidStr = line2.substring("oid ".length());

			try {
				objectIDs.add(ObjectId.fromString(oidStr));
			} catch(InvalidObjectIdException e) {
				throw new PackProtocolException(MessageFormat
						.format(JGitText.get().invalidObject, oidStr), e);
			}
		}

		return builder.setObjectIDs(objectIDs).build();
	}
}
