/*
 * Copyright (C) 2008, 2010 Google Inc.
 * Copyright (C) 2008, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2008, 2022 Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.MessageFormat;
import java.util.*;

import org.eclipse.jgit.errors.PackProtocolException;
import org.eclipse.jgit.errors.RemoteRepositoryException;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.MutableObjectId;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevCommitList;
import org.eclipse.jgit.revwalk.RevFlag;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.CommitTimeRevFilter;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.transport.GitProtocolConstants.MultiAck;
import org.eclipse.jgit.transport.PacketLineIn.AckNackResult;
import org.eclipse.jgit.util.StringUtils;
import org.eclipse.jgit.util.TemporaryBuffer;

public abstract class BasePackFetchConnection extends BasePackConnection
		implements FetchConnection {

	private static final int MAX_HAVES = 256;

	protected static final int MIN_CLIENT_BUFFER = 2 * 32 * 46 + 8;

	public static final String OPTION_INCLUDE_TAG = GitProtocolConstants.OPTION_INCLUDE_TAG;
	public static final String OPTION_MULTI_ACK = GitProtocolConstants.OPTION_MULTI_ACK;
	public static final String OPTION_MULTI_ACK_DETAILED = GitProtocolConstants.OPTION_MULTI_ACK_DETAILED;
	public static final String OPTION_THIN_PACK = GitProtocolConstants.OPTION_THIN_PACK;
	public static final String OPTION_SIDE_BAND = GitProtocolConstants.OPTION_SIDE_BAND;
	public static final String OPTION_SIDE_BAND_64K = GitProtocolConstants.OPTION_SIDE_BAND_64K;
	public static final String OPTION_OFS_DELTA = GitProtocolConstants.OPTION_OFS_DELTA;
	public static final String OPTION_NO_PROGRESS = GitProtocolConstants.OPTION_NO_PROGRESS;
	public static final String OPTION_NO_DONE = GitProtocolConstants.OPTION_NO_DONE;
	public static final String OPTION_FILTER = GitProtocolConstants.OPTION_FILTER;

	private final RevWalk walk;
	private RevCommitList<RevCommit> reachableCommits;

	final RevFlag REACHABLE;
	final RevFlag COMMON;
	private final RevFlag STATE;
	final RevFlag ADVERTISED;

	private MultiAck multiAck = MultiAck.OFF;

	private boolean thinPack;
	private boolean sideband;
	private boolean includeTags;
	private final boolean allowOfsDelta;
	private boolean noDone;
	private boolean noProgress;
	private String lockMessage;
	private PackLock packLock;
	private final int maxHaves;
	private TemporaryBuffer.Heap state;
	private PacketLineOut pckState;
	private final FilterSpec filterSpec;

	public BasePackFetchConnection(PackTransport packTransport) {
		super(packTransport);

		if(local != null) {
			final FetchConfig cfg = getFetchConfig();
			allowOfsDelta = cfg.allowOfsDelta;
			maxHaves = cfg.maxHaves;
		} else {
			allowOfsDelta = true;
			maxHaves = Integer.MAX_VALUE;
		}

		includeTags = transport.getTagOpt() != TagOpt.NO_TAGS;
		thinPack = transport.isFetchThin();
		filterSpec = transport.getFilterSpec();

		if(local != null) {
			walk = new RevWalk(local);
			walk.setRetainBody(false);
			reachableCommits = new RevCommitList<>();
			REACHABLE = walk.newFlag("REACHABLE");
			COMMON = walk.newFlag("COMMON");
			STATE = walk.newFlag("STATE");
			ADVERTISED = walk.newFlag("ADVERTISED");

			walk.carry(COMMON);
			walk.carry(REACHABLE);
			walk.carry(ADVERTISED);
		} else {
			walk = null;
			REACHABLE = null;
			COMMON = null;
			STATE = null;
			ADVERTISED = null;
		}
	}

	static class FetchConfig {
		final boolean allowOfsDelta;

		final int maxHaves;

		FetchConfig(Config c) {
			allowOfsDelta = c.getBoolean("repack", "usedeltabaseoffset", true);
			maxHaves = c.getInt("fetch", "maxhaves", Integer.MAX_VALUE);
		}

	}

	@Override
	public final void fetch(final ProgressMonitor monitor,
							final Collection<Ref> want, final Set<ObjectId> have)
			throws TransportException {
		fetch(monitor, want, have, null);
	}

	@Override
	public final void fetch(final ProgressMonitor monitor,
							final Collection<Ref> want, final Set<ObjectId> have,
							OutputStream outputStream) throws TransportException {
		markStartedOperation();
		doFetch(monitor, want, have, outputStream);
	}

	@Override
	public boolean didFetchIncludeTags() {
		return false;
	}

	@Override
	public boolean didFetchTestConnectivity() {
		return false;
	}

	@Override
	public void setPackLockMessage(String message) {
		lockMessage = message;
	}

	@Override
	public Collection<PackLock> getPackLocks() {
		if(packLock != null)
			return Collections.singleton(packLock);
		return Collections.emptyList();
	}

	private void clearState() {
		walk.dispose();
		reachableCommits = null;
		state = null;
		pckState = null;
	}

	protected void doFetch(final ProgressMonitor monitor,
						   final Collection<Ref> want, final Set<ObjectId> have,
						   OutputStream outputStream) throws TransportException {
		try {
			noProgress = monitor == NullProgressMonitor.INSTANCE;

			markRefsAdvertised();
			markReachable(have, maxTimeWanted(want));

			if(TransferConfig.ProtocolVersion.V2
					.equals(getProtocolVersion())) {
				state = new TemporaryBuffer.Heap(Integer.MAX_VALUE);
				pckState = new PacketLineOut(state);
				try {
					doFetchV2(monitor, want, outputStream);
				} finally {
					clearState();
				}
				return;
			}

			if(statelessRPC) {
				state = new TemporaryBuffer.Heap(Integer.MAX_VALUE);
				pckState = new PacketLineOut(state);
			}
			PacketLineOut output = statelessRPC ? pckState : pckOut;
			if(sendWants(want, output)) {
				output.end();
				outNeedsEnd = false;
				negotiate(monitor);

				clearState();

				receivePack(monitor, outputStream);
			}
		} catch(CancelledException ce) {
			close();
		} catch(IOException | RuntimeException err) {
			close();
			throw new TransportException(err.getMessage(), err);
		}
	}

	private void doFetchV2(ProgressMonitor monitor, Collection<Ref> want,
						   OutputStream outputStream) throws IOException, CancelledException {
		sideband = true;
		negotiateBegin();

		pckState.writeString("command=" + GitProtocolConstants.COMMAND_FETCH);
		String agent = UserAgent.get();
		if(agent != null && isCapableOf(GitProtocolConstants.OPTION_AGENT)) {
			pckState.writeString(
					GitProtocolConstants.OPTION_AGENT + '=' + agent);
		}
		Set<String> capabilities = new HashSet<>();
		String advertised = getCapability();
		if(!StringUtils.isEmptyOrNull(advertised)) {
			capabilities.addAll(Arrays.asList(advertised.split("\\s+")));
		}

		pckState.writeDelim();
		for(String capability : getCapabilitiesV2(capabilities)) {
			pckState.writeString(capability);
		}
		if(!sendWants(want, pckState)) {
			return;
		}

		outNeedsEnd = false;

		FetchStateV2 fetchState = new FetchStateV2();
		boolean sentDone;
		for(; ; ) {
			state.writeTo(out, monitor);
			sentDone = sendNextHaveBatch(fetchState, pckOut, monitor);
			if(sentDone) {
				break;
			}
			if(readAcknowledgments(fetchState, pckIn, monitor)) {
				break;
			}
		}
		clearState();
		String line = pckIn.readString();
		if(sentDone && line.startsWith("ERR ")) {
			throw new RemoteRepositoryException(uri, line.substring(4));
		}
		if(!GitProtocolConstants.SECTION_PACKFILE.equals(line)) {
			throw new PackProtocolException(
					MessageFormat.format(JGitText.get().expectedGot,
							GitProtocolConstants.SECTION_PACKFILE, line));
		}
		receivePack(monitor, outputStream);
	}

	private boolean sendNextHaveBatch(FetchStateV2 fetchState,
									  PacketLineOut output, ProgressMonitor monitor)
			throws IOException, CancelledException {
		long n = 0;
		while(n < fetchState.havesToSend) {
			final RevCommit c = walk.next();
			if(c == null) {
				break;
			}
			output.writeString("have " + c.getId().name() + '\n');
			n++;
			if(n % 10 == 0 && monitor.isCancelled()) {
				throw new CancelledException();
			}
		}
		fetchState.havesTotal += n;
		if(n == 0
				|| (fetchState.hadAcks
				&& fetchState.havesWithoutAck > MAX_HAVES)
				|| fetchState.havesTotal > maxHaves) {
			output.writeString("done\n");
			output.end();
			return true;
		}

		fetchState.havesWithoutAck += n;
		output.end();
		fetchState.incHavesToSend(statelessRPC);
		return false;
	}

	private boolean readAcknowledgments(FetchStateV2 fetchState,
										PacketLineIn input, ProgressMonitor monitor)
			throws IOException, CancelledException {
		String line = input.readString();
		if(!GitProtocolConstants.SECTION_ACKNOWLEDGMENTS.equals(line)) {
			throw new PackProtocolException(MessageFormat.format(
					JGitText.get().expectedGot,
					GitProtocolConstants.SECTION_ACKNOWLEDGMENTS, line));
		}
		MutableObjectId returnedId = new MutableObjectId();
		line = input.readString();
		boolean gotReady = false;
		long n = 0;
		while(!PacketLineIn.isEnd(line) && !PacketLineIn.isDelimiter(line)) {
			AckNackResult ack = PacketLineIn.parseACKv2(line, returnedId);
			if(!gotReady) {
				if(ack == AckNackResult.ACK_COMMON) {
					markCommon(walk.parseAny(returnedId), ack, true);
					fetchState.havesWithoutAck = 0;
					fetchState.hadAcks = true;
				} else if(ack == AckNackResult.ACK_READY) {
					gotReady = true;
				}
			}
			n++;
			if(n % 10 == 0 && monitor.isCancelled()) {
				throw new CancelledException();
			}
			line = input.readString();
		}
		if(gotReady) {
			if(!PacketLineIn.isDelimiter(line)) {
				throw new PackProtocolException(MessageFormat
						.format(JGitText.get().expectedGot, "0001", line));
			}
		} else if(!PacketLineIn.isEnd(line)) {
			throw new PackProtocolException(MessageFormat
					.format(JGitText.get().expectedGot, "0000", line));
		}
		return gotReady;
	}

	@Override
	public void close() {
		if(walk != null)
			walk.close();
		super.close();
	}

	FetchConfig getFetchConfig() {
		return local.getConfig().get(FetchConfig::new);
	}

	private int maxTimeWanted(Collection<Ref> wants) {
		int maxTime = 0;
		for(Ref r : wants) {
			try {
				final RevObject obj = walk.parseAny(r.getObjectId());
				if(obj instanceof RevCommit) {
					final int cTime = ((RevCommit) obj).getCommitTime();
					if(maxTime < cTime)
						maxTime = cTime;
				}
			} catch(IOException ignored) {
			}
		}
		return maxTime;
	}

	private void markReachable(Set<ObjectId> have, int maxTime)
			throws IOException {
		for(Ref r : local.getRefDatabase().getRefs()) {
			ObjectId id = r.getPeeledObjectId();
			if(id == null)
				id = r.getObjectId();
			if(id == null)
				continue;
			parseReachable(id);
		}

		for(ObjectId id : local.getAdditionalHaves())
			parseReachable(id);

		for(ObjectId id : have)
			parseReachable(id);

		if(maxTime > 0) {
			final Date maxWhen = new Date(maxTime * 1000L);
			walk.sort(RevSort.COMMIT_TIME_DESC);
			walk.markStart(reachableCommits);
			walk.setRevFilter(CommitTimeRevFilter.after(maxWhen));
			for(; ; ) {
				final RevCommit c = walk.next();
				if(c == null)
					break;
				if(c.has(ADVERTISED) && !c.has(COMMON)) {
					c.add(COMMON);
					c.carry(COMMON);
					reachableCommits.add(c);
				}
			}
		}
	}

	private void parseReachable(ObjectId id) {
		try {
			RevCommit o = walk.parseCommit(id);
			if(!o.has(REACHABLE)) {
				o.add(REACHABLE);
				reachableCommits.add(o);
			}
		} catch(IOException ignored) {
		}
	}

	private boolean sendWants(Collection<Ref> want, PacketLineOut p)
			throws IOException {
		boolean first = true;
		for(Ref r : want) {
			ObjectId objectId = r.getObjectId();
			if(objectId == null) {
				continue;
			}
			try {
				if(walk.parseAny(objectId).has(REACHABLE)) {
					continue;
				}
			} catch(IOException ignored) {
			}

			final StringBuilder line = new StringBuilder(46);
			line.append("want ");
			line.append(objectId.name());
			if(first && TransferConfig.ProtocolVersion.V0
					.equals(getProtocolVersion())) {
				line.append(enableCapabilities());
			}
			first = false;
			line.append('\n');
			p.writeString(line.toString());
		}
		if(first) {
			return false;
		}
		if(!filterSpec.isNoOp()) {
			p.writeString(filterSpec.filterLine());
		}
		return true;
	}

	private Set<String> getCapabilitiesV2(Set<String> advertisedCapabilities)
			throws TransportException {
		Set<String> capabilities = new LinkedHashSet<>();
		if(noProgress) {
			capabilities.add(OPTION_NO_PROGRESS);
		}
		if(includeTags) {
			capabilities.add(OPTION_INCLUDE_TAG);
		}
		if(allowOfsDelta) {
			capabilities.add(OPTION_OFS_DELTA);
		}
		if(thinPack) {
			capabilities.add(OPTION_THIN_PACK);
		}
		if(!filterSpec.isNoOp()
				&& !advertisedCapabilities.contains(OPTION_FILTER)) {
			throw new PackProtocolException(uri,
					JGitText.get().filterRequiresCapability);
		}

		return capabilities;
	}

	private String enableCapabilities() throws TransportException {
		final StringBuilder line = new StringBuilder();
		if(noProgress)
			wantCapability(line, OPTION_NO_PROGRESS);
		if(includeTags)
			includeTags = wantCapability(line, OPTION_INCLUDE_TAG);
		if(allowOfsDelta)
			wantCapability(line, OPTION_OFS_DELTA);

		if(wantCapability(line, OPTION_MULTI_ACK_DETAILED)) {
			multiAck = MultiAck.DETAILED;
			if(statelessRPC)
				noDone = wantCapability(line, OPTION_NO_DONE);
		} else if(wantCapability(line, OPTION_MULTI_ACK))
			multiAck = MultiAck.CONTINUE;
		else
			multiAck = MultiAck.OFF;

		if(thinPack)
			thinPack = wantCapability(line, OPTION_THIN_PACK);
		if(wantCapability(line, OPTION_SIDE_BAND_64K))
			sideband = true;
		else if(wantCapability(line, OPTION_SIDE_BAND))
			sideband = true;

		if(statelessRPC && multiAck != MultiAck.DETAILED) {
			throw new PackProtocolException(uri, MessageFormat.format(
					JGitText.get().statelessRPCRequiresOptionToBeEnabled,
					OPTION_MULTI_ACK_DETAILED));
		}

		if(!filterSpec.isNoOp() && !wantCapability(line, OPTION_FILTER)) {
			throw new PackProtocolException(uri,
					JGitText.get().filterRequiresCapability);
		}

		addUserAgentCapability(line);
		return line.toString();
	}

	private void negotiate(ProgressMonitor monitor) throws IOException,
			CancelledException {
		final MutableObjectId ackId = new MutableObjectId();
		int resultsPending = 0;
		int havesSent = 0;
		int havesSinceLastContinue = 0;
		boolean receivedContinue = false;
		boolean receivedAck = false;
		boolean receivedReady = false;

		if(statelessRPC) {
			state.writeTo(out, null);
		}

		negotiateBegin();
		SEND_HAVES:
		for(; ; ) {
			final RevCommit c = walk.next();
			if(c == null) {
				break;
			}

			ObjectId o = c.getId();
			pckOut.writeString("have " + o.name() + "\n");
			havesSent++;
			havesSinceLastContinue++;

			if((31 & havesSent) != 0) {
				continue;
			}

			if(monitor.isCancelled()) {
				throw new CancelledException();
			}

			pckOut.end();
			resultsPending++;

			if(havesSent == 32 && !statelessRPC) {
				continue;
			}

			READ_RESULT:
			for(; ; ) {
				final AckNackResult anr = pckIn.readACK(ackId);
				switch(anr) {
					case NAK:
						resultsPending--;
						break READ_RESULT;

					case ACK:
						multiAck = MultiAck.OFF;
						resultsPending = 0;
						receivedAck = true;
						if(statelessRPC) {
							state.writeTo(out, null);
						}
						break SEND_HAVES;

					case ACK_CONTINUE:
					case ACK_COMMON:
					case ACK_READY:
						markCommon(walk.parseAny(ackId), anr, statelessRPC);
						receivedAck = true;
						receivedContinue = true;
						havesSinceLastContinue = 0;
						if(anr == AckNackResult.ACK_READY) {
							receivedReady = true;
						}
						break;
				}

				if(monitor.isCancelled()) {
					throw new CancelledException();
				}
			}

			if(noDone && receivedReady) {
				break;
			}
			if(statelessRPC) {
				state.writeTo(out, null);
			}

			if((receivedContinue && havesSinceLastContinue > MAX_HAVES)
					|| havesSent >= maxHaves) {
				break;
			}
		}

		if(monitor.isCancelled()) {
			throw new CancelledException();
		}

		if(!receivedReady || !noDone) {
			pckOut.writeString("done\n");
			pckOut.flush();
		}

		if(!receivedAck) {
			multiAck = MultiAck.OFF;
			resultsPending++;
		}

		READ_RESULT:
		while(resultsPending > 0 || multiAck != MultiAck.OFF) {
			final AckNackResult anr = pckIn.readACK(ackId);
			resultsPending--;
			switch(anr) {
				case NAK:
					break;

				case ACK:
					break READ_RESULT;

				case ACK_CONTINUE:
				case ACK_COMMON:
				case ACK_READY:
					multiAck = MultiAck.CONTINUE;
					break;
			}

			if(monitor.isCancelled()) {
				throw new CancelledException();
			}
		}
	}

	private void negotiateBegin() throws IOException {
		walk.resetRetain(REACHABLE, ADVERTISED);
		walk.markStart(reachableCommits);
		walk.sort(RevSort.COMMIT_TIME_DESC);
		walk.setRevFilter(new RevFilter() {
			@Override
			public RevFilter clone() {
				return this;
			}

			@Override
			public boolean include(RevWalk walker, RevCommit c) {
				final boolean remoteKnowsIsCommon = c.has(Objects.requireNonNull(COMMON));
				if(c.has(Objects.requireNonNull(ADVERTISED))) {
					c.add(COMMON);
				}
				return !remoteKnowsIsCommon;
			}

			@Override
			public boolean requiresCommitBody() {
				return false;
			}
		});
	}

	private void markRefsAdvertised() {
		for(Ref r : getRefs()) {
			markAdvertised(r.getObjectId());
			if(r.getPeeledObjectId() != null)
				markAdvertised(r.getPeeledObjectId());
		}
	}

	private void markAdvertised(AnyObjectId id) {
		try {
			walk.parseAny(id).add(ADVERTISED);
		} catch(IOException ignored) {
		}
	}

	private void markCommon(RevObject obj, AckNackResult anr, boolean useState)
			throws IOException {
		if(useState && anr == AckNackResult.ACK_COMMON && !obj.has(STATE)) {
			pckState.writeString("have " + obj.name() + '\n');
			obj.add(STATE);
		}
		obj.add(COMMON);
		if(obj instanceof RevCommit)
			((RevCommit) obj).carry(COMMON);
	}

	private void receivePack(final ProgressMonitor monitor,
							 OutputStream outputStream) throws IOException {
		onReceivePack();
		InputStream input = in;
		SideBandInputStream sidebandIn = null;
		if(sideband) {
			sidebandIn = new SideBandInputStream(input, monitor,
					getMessageWriter(), outputStream);
			input = sidebandIn;
		}

		try(ObjectInserter ins = local.newObjectInserter()) {
			PackParser parser = ins.newPackParser(input);
			parser.setAllowThin(thinPack);
			parser.setObjectChecker(transport.getObjectChecker());
			parser.setLockMessage(lockMessage);
			packLock = parser.parse(monitor);
			ins.flush();
		} finally {
			if(sidebandIn != null) {
				sidebandIn.drainMessages();
			}
		}
	}

	protected void onReceivePack() {
	}

	private static class CancelledException extends Exception {
		private static final long serialVersionUID = 1L;
	}

	private static class FetchStateV2 {

		long havesToSend = 32;
		long havesTotal;
		boolean hadAcks;
		long havesWithoutAck;

		void incHavesToSend(boolean statelessRPC) {
			if(statelessRPC) {
				if(havesToSend < 16384) {
					havesToSend *= 2;
				} else {
					havesToSend = havesToSend * 11 / 10;
				}
			} else {
				havesToSend += 32;
			}
		}
	}
}
