/*
 * Copyright (C) 2008, 2010 Google Inc.
 * Copyright (C) 2008, Marek Zawirski <marek.zawirski@gmail.com>
 * Copyright (C) 2008, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2008, 2020 Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import static org.eclipse.jgit.transport.GitProtocolConstants.COMMAND_LS_REFS;
import static org.eclipse.jgit.transport.GitProtocolConstants.OPTION_AGENT;
import static org.eclipse.jgit.transport.GitProtocolConstants.REF_ATTR_PEELED;
import static org.eclipse.jgit.transport.GitProtocolConstants.REF_ATTR_SYMREF_TARGET;
import static org.eclipse.jgit.transport.GitProtocolConstants.VERSION_1;
import static org.eclipse.jgit.transport.GitProtocolConstants.VERSION_2;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.errors.InvalidObjectIdException;
import org.eclipse.jgit.errors.NoRemoteRepositoryException;
import org.eclipse.jgit.errors.PackProtocolException;
import org.eclipse.jgit.errors.RemoteRepositoryException;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectIdRef;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.SymbolicRef;
import org.eclipse.jgit.util.StringUtils;
import org.eclipse.jgit.util.io.InterruptTimer;
import org.eclipse.jgit.util.io.TimeoutInputStream;
import org.eclipse.jgit.util.io.TimeoutOutputStream;

abstract class BasePackConnection extends BaseConnection {

	protected static final String CAPABILITY_SYMREF_PREFIX = "symref=";

	protected final Repository local;
	protected final URIish uri;
	protected final Transport transport;
	protected TimeoutInputStream timeoutIn;
	protected TimeoutOutputStream timeoutOut;
	private InterruptTimer myTimer;
	protected InputStream in;
	protected OutputStream out;
	protected PacketLineIn pckIn;
	protected PacketLineOut pckOut;
	protected boolean outNeedsEnd;
	protected boolean statelessRPC;
	private final Map<String, String> remoteCapabilities = new HashMap<>();
	protected final Set<ObjectId> additionalHaves = new HashSet<>();
	private TransferConfig.ProtocolVersion protocol = TransferConfig.ProtocolVersion.V0;

	BasePackConnection(PackTransport packTransport) {
		transport = (Transport) packTransport;
		local = transport.local;
		uri = transport.uri;
	}

	TransferConfig.ProtocolVersion getProtocolVersion() {
		return protocol;
	}

	void setProtocolVersion(@NonNull TransferConfig.ProtocolVersion protocol) {
		this.protocol = protocol;
	}

	protected final void init(InputStream myIn, OutputStream myOut) {
		final int timeout = transport.getTimeout();
		if(timeout > 0) {
			final Thread caller = Thread.currentThread();
			if(myTimer == null) {
				myTimer = new InterruptTimer(caller.getName() + "-Timer");
			}
			timeoutIn = new TimeoutInputStream(myIn, myTimer);
			timeoutOut = new TimeoutOutputStream(myOut, myTimer);
			timeoutIn.setTimeout(timeout * 1000);
			timeoutOut.setTimeout(timeout * 1000);
			myIn = timeoutIn;
			myOut = timeoutOut;
		}

		in = myIn;
		out = myOut;

		pckIn = new PacketLineIn(in);
		pckOut = new PacketLineOut(out);
		outNeedsEnd = true;
	}

	protected boolean readAdvertisedRefs() throws TransportException {
		try {
			return readAdvertisedRefsImpl();
		} catch(TransportException err) {
			close();
			throw err;
		} catch(IOException | RuntimeException err) {
			close();
			throw new TransportException(err.getMessage(), err);
		}
	}

	private String readLine() throws IOException {
		String line = pckIn.readString();
		if(PacketLineIn.isEnd(line)) {
			return null;
		}
		if(line.startsWith("ERR ")) {
			throw new RemoteRepositoryException(uri, line.substring(4));
		}
		return line;
	}

	private boolean readAdvertisedRefsImpl() throws IOException {
		final Map<String, Ref> avail = new LinkedHashMap<>();
		final Map<String, String> symRefs = new LinkedHashMap<>();
		for(boolean first = true; ; first = false) {
			String line;

			if(first) {
				boolean isV1 = false;
				try {
					line = readLine();
				} catch(EOFException e) {
					throw noRepository(e);
				}
				if(VERSION_1.equals(line)) {
					setProtocolVersion(TransferConfig.ProtocolVersion.V0);
					isV1 = true;
					line = readLine();
				}
				if(line == null) {
					break;
				}
				final int nul = line.indexOf('\0');
				if(nul >= 0) {
					for(String capability : line.substring(nul + 1)
							.split(" ")) {
						if(capability.startsWith(CAPABILITY_SYMREF_PREFIX)) {
							String[] parts = capability
									.substring(
											CAPABILITY_SYMREF_PREFIX.length())
									.split(":", 2);
							if(parts.length == 2) {
								symRefs.put(parts[0], parts[1]);
							}
						} else {
							addCapability(capability);
						}
					}
					line = line.substring(0, nul);
					setProtocolVersion(TransferConfig.ProtocolVersion.V0);
				} else if(!isV1 && VERSION_2.equals(line)) {
					setProtocolVersion(TransferConfig.ProtocolVersion.V2);
					readCapabilitiesV2();
					return false;
				} else {
					setProtocolVersion(TransferConfig.ProtocolVersion.V0);
				}
			} else {
				line = readLine();
				if(line == null) {
					break;
				}
			}

			if(line.length() < 41 || line.charAt(40) != ' ') {
				throw invalidRefAdvertisementLine(line);
			}
			String name = line.substring(41);
			if(first && name.equals("capabilities^{}")) {
				continue;
			}

			final ObjectId id = toId(line, line.substring(0, 40));
			if(name.equals(".have")) {
				additionalHaves.add(id);
			} else {
				processLineV1(name, id, avail);
			}
		}
		updateWithSymRefs(avail, symRefs);
		available(avail);
		return true;
	}

	protected void lsRefs(Collection<RefSpec> refSpecs,
						  String... additionalPatterns) throws TransportException {
		try {
			lsRefsImpl(refSpecs, additionalPatterns);
		} catch(TransportException err) {
			close();
			throw err;
		} catch(IOException | RuntimeException err) {
			close();
			throw new TransportException(err.getMessage(), err);
		}
	}

	private void lsRefsImpl(Collection<RefSpec> refSpecs,
							String... additionalPatterns) throws IOException {
		pckOut.writeString("command=" + COMMAND_LS_REFS);
		String agent = UserAgent.get();
		if(agent != null && isCapableOf(OPTION_AGENT)) {
			pckOut.writeString(OPTION_AGENT + '=' + agent);
		}
		pckOut.writeDelim();
		pckOut.writeString("peel");
		pckOut.writeString("symrefs");
		for(String refPrefix : getRefPrefixes(refSpecs, additionalPatterns)) {
			pckOut.writeString("ref-prefix " + refPrefix);
		}
		pckOut.end();
		final Map<String, Ref> avail = new LinkedHashMap<>();
		final Map<String, String> symRefs = new LinkedHashMap<>();
		for(; ; ) {
			String line = readLine();
			if(line == null) {
				break;
			}

			if(line.length() < 41 || line.charAt(40) != ' ') {
				throw invalidRefAdvertisementLine(line);
			}
			String name = line.substring(41);
			final ObjectId id = toId(line, line.substring(0, 40));
			if(name.equals(".have")) {
				additionalHaves.add(id);
			} else {
				processLineV2(line, id, name, avail, symRefs);
			}
		}
		updateWithSymRefs(avail, symRefs);
		available(avail);
	}

	private Collection<String> getRefPrefixes(Collection<RefSpec> refSpecs,
											  String... additionalPatterns) {
		if(refSpecs.isEmpty() && (additionalPatterns == null
				|| additionalPatterns.length == 0)) {
			return Collections.emptyList();
		}
		Set<String> patterns = new HashSet<>();
		if(additionalPatterns != null) {
			Arrays.stream(additionalPatterns).filter(Objects::nonNull)
					.forEach(patterns::add);
		}
		for(RefSpec spec : refSpecs) {
			String src = spec.getSource();
			if(ObjectId.isId(src)) {
				continue;
			}
			if(spec.isWildcard()) {
				patterns.add(src.substring(0, src.indexOf('*')));
			} else {
				patterns.add(src);
				patterns.add(Constants.R_REFS + src);
				patterns.add(Constants.R_HEADS + src);
				patterns.add(Constants.R_TAGS + src);
			}
		}
		return patterns;
	}

	private void readCapabilitiesV2() throws IOException {
		for(; ; ) {
			String line = readLine();
			if(line == null) {
				break;
			}
			addCapability(line);
		}
	}

	private void addCapability(String capability) {
		String[] parts = capability.split("=", 2);
		if(parts.length == 2) {
			remoteCapabilities.put(parts[0], parts[1]);
		}
		remoteCapabilities.put(capability, null);
	}

	private ObjectId toId(String line, String value)
			throws PackProtocolException {
		try {
			return ObjectId.fromString(value);
		} catch(InvalidObjectIdException e) {
			PackProtocolException ppe = invalidRefAdvertisementLine(line);
			ppe.initCause(e);
			throw ppe;
		}
	}

	private void processLineV1(String name, ObjectId id, Map<String, Ref> avail)
			throws IOException {
		if(name.endsWith("^{}")) {
			name = name.substring(0, name.length() - 3);
			final Ref prior = avail.get(name);
			if(prior == null) {
				throw new PackProtocolException(uri, MessageFormat.format(
						JGitText.get().advertisementCameBefore, name, name));
			}
			if(prior.getPeeledObjectId() != null) {
				throw duplicateAdvertisement(name + "^{}");
			}
			avail.put(name, new ObjectIdRef.PeeledTag(Ref.Storage.NETWORK, name,
					prior.getObjectId(), id));
		} else {
			final Ref prior = avail.put(name, new ObjectIdRef.PeeledNonTag(
					Ref.Storage.NETWORK, name, id));
			if(prior != null) {
				throw duplicateAdvertisement(name);
			}
		}
	}

	private void processLineV2(String line, ObjectId id, String rest,
							   Map<String, Ref> avail, Map<String, String> symRefs)
			throws IOException {
		String[] parts = rest.split(" ");
		String name = parts[0];
		String symRefTarget = null;
		String peeled = null;
		for(int i = 1; i < parts.length; i++) {
			if(parts[i].startsWith(REF_ATTR_SYMREF_TARGET)) {
				if(symRefTarget != null) {
					throw new PackProtocolException(uri, MessageFormat.format(
							JGitText.get().duplicateRefAttribute, line));
				}
				symRefTarget = parts[i]
						.substring(REF_ATTR_SYMREF_TARGET.length());
			} else if(parts[i].startsWith(REF_ATTR_PEELED)) {
				if(peeled != null) {
					throw new PackProtocolException(uri, MessageFormat.format(
							JGitText.get().duplicateRefAttribute, line));
				}
				peeled = parts[i].substring(REF_ATTR_PEELED.length());
			}
			if(peeled != null && symRefTarget != null) {
				break;
			}
		}
		Ref idRef;
		if(peeled != null) {
			idRef = new ObjectIdRef.PeeledTag(Ref.Storage.NETWORK, name, id,
					toId(line, peeled));
		} else {
			idRef = new ObjectIdRef.PeeledNonTag(Ref.Storage.NETWORK, name, id);
		}
		Ref prior = avail.put(name, idRef);
		if(prior != null) {
			throw duplicateAdvertisement(name);
		}
		if(!StringUtils.isEmptyOrNull(symRefTarget)) {
			symRefs.put(name, symRefTarget);
		}
	}

	static void updateWithSymRefs(Map<String, Ref> refMap, Map<String, String> symRefs) {
		boolean haveNewRefMapEntries = !refMap.isEmpty();
		while(!symRefs.isEmpty() && haveNewRefMapEntries) {
			haveNewRefMapEntries = false;
			final Iterator<Map.Entry<String, String>> iterator = symRefs.entrySet().iterator();
			while(iterator.hasNext()) {
				final Map.Entry<String, String> symRef = iterator.next();
				if(!symRefs.containsKey(symRef.getValue())) {
					final Ref r = refMap.get(symRef.getValue());
					if(r != null) {
						refMap.put(symRef.getKey(), new SymbolicRef(symRef.getKey(), r));
						haveNewRefMapEntries = true;
						iterator.remove();
					}
				}
			}
		}

		String headRefName = symRefs.get(Constants.HEAD);
		if(headRefName != null && !refMap.containsKey(headRefName)) {
			Ref headRef = refMap.get(Constants.HEAD);
			if(headRef != null) {
				ObjectId headObj = headRef.getObjectId();
				headRef = new ObjectIdRef.PeeledNonTag(Ref.Storage.NETWORK,
						headRefName, headObj);
				refMap.put(headRefName, headRef);
				headRef = new SymbolicRef(Constants.HEAD, headRef);
				refMap.put(Constants.HEAD, headRef);
				symRefs.remove(Constants.HEAD);
			}
		}
	}

	protected TransportException noRepository(Throwable cause) {
		return new NoRemoteRepositoryException(uri, JGitText.get().notFound,
				cause);
	}

	protected boolean isCapableOf(String option) {
		return remoteCapabilities.containsKey(option);
	}

	protected boolean wantCapability(StringBuilder b, String option) {
		if(!isCapableOf(option))
			return false;
		b.append(' ');
		b.append(option);
		return true;
	}

	protected String getCapability() {
		return remoteCapabilities.get(GitProtocolConstants.COMMAND_FETCH);
	}

	protected void addUserAgentCapability(StringBuilder b) {
		String a = UserAgent.get();
		if(a != null && remoteCapabilities.get(OPTION_AGENT) != null) {
			b.append(' ').append(OPTION_AGENT).append('=').append(a);
		}
	}

	@Override
	public String getPeerUserAgent() {
		String agent = remoteCapabilities.get(OPTION_AGENT);
		return agent != null ? agent : super.getPeerUserAgent();
	}

	private PackProtocolException duplicateAdvertisement(String name) {
		return new PackProtocolException(uri, MessageFormat.format(JGitText.get().duplicateAdvertisementsOf, name));
	}

	private PackProtocolException invalidRefAdvertisementLine(String line) {
		return new PackProtocolException(uri, MessageFormat.format(JGitText.get().invalidRefAdvertisementLine, line));
	}

	@Override
	public void close() {
		if(out != null) {
			try {
				if(outNeedsEnd) {
					outNeedsEnd = false;
					pckOut.end();
				}
				out.close();
			} catch(IOException ignored) {
			} finally {
				out = null;
				pckOut = null;
			}
		}

		if(in != null) {
			try {
				in.close();
			} catch(IOException ignored) {
			} finally {
				in = null;
				pckIn = null;
			}
		}

		if(myTimer != null) {
			try {
				myTimer.terminate();
			} finally {
				myTimer = null;
				timeoutIn = null;
				timeoutOut = null;
			}
		}
	}

	protected void endOut() {
		if(outNeedsEnd && out != null) {
			try {
				outNeedsEnd = false;
				pckOut.end();
			} catch(IOException e) {
				try {
					out.close();
				} catch(IOException ignored) {
				} finally {
					out = null;
					pckOut = null;
				}
			}
		}
	}
}
