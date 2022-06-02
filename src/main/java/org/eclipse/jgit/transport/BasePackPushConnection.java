/*
 * Copyright (C) 2008, Marek Zawirski <marek.zawirski@gmail.com>
 * Copyright (C) 2008, 2022 Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import static org.eclipse.jgit.transport.GitProtocolConstants.CAPABILITY_ATOMIC;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.MessageFormat;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jgit.errors.NoRemoteRepositoryException;
import org.eclipse.jgit.errors.NotSupportedException;
import org.eclipse.jgit.errors.PackProtocolException;
import org.eclipse.jgit.errors.TooLargeObjectInPackException;
import org.eclipse.jgit.errors.TooLargePackException;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.internal.storage.pack.PackWriter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.RemoteRefUpdate.Status;

public abstract class BasePackPushConnection extends BasePackConnection implements
		PushConnection {

	public static final String CAPABILITY_REPORT_STATUS = GitProtocolConstants.CAPABILITY_REPORT_STATUS;
	public static final String CAPABILITY_DELETE_REFS = GitProtocolConstants.CAPABILITY_DELETE_REFS;
	public static final String CAPABILITY_OFS_DELTA = GitProtocolConstants.CAPABILITY_OFS_DELTA;
	public static final String CAPABILITY_SIDE_BAND_64K = GitProtocolConstants.CAPABILITY_SIDE_BAND_64K;
	public static final String CAPABILITY_PUSH_OPTIONS = GitProtocolConstants.CAPABILITY_PUSH_OPTIONS;

	private final boolean thinPack;
	private final boolean atomic;

	private final List<String> pushOptions;

	private boolean capableAtomic;
	private boolean capableDeleteRefs;
	private boolean capableReport;
	private boolean capableSideBand;
	private boolean capableOfsDelta;
	private boolean capablePushOptions;

	private boolean sentCommand;
	private boolean writePack;

	private long packTransferTime;

	public BasePackPushConnection(PackTransport packTransport) {
		super(packTransport);
		thinPack = transport.isPushThin();
		atomic = transport.isPushAtomic();
		pushOptions = transport.getPushOptions();
	}

	@Override
	public void push(final ProgressMonitor monitor,
					 final Map<String, RemoteRefUpdate> refUpdates)
			throws TransportException {
		push(monitor, refUpdates, null);
	}

	@Override
	public void push(final ProgressMonitor monitor,
					 final Map<String, RemoteRefUpdate> refUpdates, OutputStream outputStream)
			throws TransportException {
		markStartedOperation();
		doPush(monitor, refUpdates, outputStream);
	}

	@Override
	protected TransportException noRepository(Throwable cause) {
		TransportException te;
		try {
			transport.openFetch().close();
			te = new TransportException(uri, JGitText.get().pushNotPermitted);
		} catch(NoRemoteRepositoryException e) {
			te = e;
		} catch(NotSupportedException | TransportException e) {
			te = new TransportException(uri, JGitText.get().pushNotPermitted, e);
		}
		te.addSuppressed(cause);
		return te;
	}

	protected void doPush(final ProgressMonitor monitor,
						  final Map<String, RemoteRefUpdate> refUpdates,
						  OutputStream outputStream) throws TransportException {
		try {
			writeCommands(refUpdates.values(), monitor, outputStream);

			if(pushOptions != null && capablePushOptions)
				transmitOptions();
			if(writePack)
				writePack(refUpdates, monitor);
			if(sentCommand) {
				if(capableReport)
					readStatusReport(refUpdates);
				if(capableSideBand) {
					int b = in.read();
					if(0 <= b) {
						throw new TransportException(uri, MessageFormat.format(
								JGitText.get().expectedEOFReceived,
								(char) b));
					}
				}
			}
		} catch(TransportException e) {
			throw e;
		} catch(Exception e) {
			throw new TransportException(uri, e.getMessage(), e);
		} finally {
			if(in instanceof SideBandInputStream) {
				((SideBandInputStream) in).drainMessages();
			}
			close();
		}
	}

	private void writeCommands(final Collection<RemoteRefUpdate> refUpdates,
							   final ProgressMonitor monitor, OutputStream outputStream) throws IOException {
		final String capabilities = enableCapabilities(monitor, outputStream);
		if(atomic && !capableAtomic) {
			throw new TransportException(uri,
					JGitText.get().atomicPushNotSupported);
		}

		if(pushOptions != null && !capablePushOptions) {
			throw new TransportException(uri,
					MessageFormat.format(JGitText.get().pushOptionsNotSupported,
							pushOptions.toString()));
		}

		for(RemoteRefUpdate rru : refUpdates) {
			if(!capableDeleteRefs && rru.isDelete()) {
				rru.setStatus(Status.REJECTED_NODELETE);
				continue;
			}

			final StringBuilder sb = new StringBuilder();
			ObjectId oldId = rru.getExpectedOldObjectId();
			if(oldId == null) {
				final Ref advertised = getRef(rru.getRemoteName());
				oldId = advertised != null ? advertised.getObjectId() : null;
				if(oldId == null) {
					oldId = ObjectId.zeroId();
				}
			}
			sb.append(oldId.name());
			sb.append(' ');
			sb.append(rru.getNewObjectId().name());
			sb.append(' ');
			sb.append(rru.getRemoteName());
			if(!sentCommand) {
				sentCommand = true;
				sb.append(capabilities);
			}

			pckOut.writeString(sb.toString());
			rru.setStatus(Status.AWAITING_REPORT);
			if(!rru.isDelete())
				writePack = true;
		}

		if(monitor.isCancelled())
			throw new TransportException(uri, JGitText.get().pushCancelled);
		pckOut.end();
		outNeedsEnd = false;
	}

	private void transmitOptions() throws IOException {
		for(String pushOption : pushOptions) {
			pckOut.writeString(pushOption);
		}

		pckOut.end();
	}

	private String enableCapabilities(final ProgressMonitor monitor,
									  OutputStream outputStream) {
		final StringBuilder line = new StringBuilder();
		if(atomic)
			capableAtomic = wantCapability(line, CAPABILITY_ATOMIC);
		capableReport = wantCapability(line, CAPABILITY_REPORT_STATUS);
		capableDeleteRefs = wantCapability(line, CAPABILITY_DELETE_REFS);
		capableOfsDelta = wantCapability(line, CAPABILITY_OFS_DELTA);

		if(pushOptions != null) {
			capablePushOptions = wantCapability(line, CAPABILITY_PUSH_OPTIONS);
		}

		capableSideBand = wantCapability(line, CAPABILITY_SIDE_BAND_64K);
		if(capableSideBand) {
			in = new SideBandInputStream(in, monitor, getMessageWriter(),
					outputStream);
			pckIn = new PacketLineIn(in);
		}
		addUserAgentCapability(line);

		if(line.length() > 0)
			line.setCharAt(0, '\0');
		return line.toString();
	}

	private void writePack(final Map<String, RemoteRefUpdate> refUpdates,
						   final ProgressMonitor monitor) throws IOException {
		Set<ObjectId> remoteObjects = new HashSet<>();
		Set<ObjectId> newObjects = new HashSet<>();

		try(PackWriter writer = new PackWriter(transport.getPackConfig(),
				local.newObjectReader())) {

			for(Ref r : getRefs()) {
				ObjectId oid = r.getObjectId();
				if(local.getObjectDatabase().has(oid))
					remoteObjects.add(oid);
			}
			remoteObjects.addAll(additionalHaves);
			for(RemoteRefUpdate r : refUpdates.values()) {
				if(!ObjectId.zeroId().equals(r.getNewObjectId()))
					newObjects.add(r.getNewObjectId());
			}

			writer.setIndexDisabled(true);
			writer.setUseCachedPacks(true);
			writer.setUseBitmaps(true);
			writer.setThin(thinPack);
			writer.setReuseValidatingObjects(false);
			writer.setDeltaBaseAsOffset(capableOfsDelta);
			writer.preparePack(monitor, newObjects, remoteObjects);

			OutputStream packOut = out;
			if(capableSideBand) {
				packOut = new CheckingSideBandOutputStream(in, out);
			}
			writer.writePack(monitor, monitor, packOut);

			packTransferTime = writer.getStatistics().getTimeWriting();
		}
	}

	private void readStatusReport(Map<String, RemoteRefUpdate> refUpdates)
			throws IOException {
		final String unpackLine = readStringLongTimeout();
		if(!unpackLine.startsWith("unpack "))
			throw new PackProtocolException(uri, MessageFormat
					.format(JGitText.get().unexpectedReportLine, unpackLine));
		final String unpackStatus = unpackLine.substring("unpack ".length());
		if(unpackStatus.startsWith("error Pack exceeds the limit of")) {
			throw new TooLargePackException(uri,
					unpackStatus.substring("error ".length()));
		} else if(unpackStatus.startsWith("error Object too large")) {
			throw new TooLargeObjectInPackException(uri,
					unpackStatus.substring("error ".length()));
		} else if(!unpackStatus.equals("ok")) {
			throw new TransportException(uri, MessageFormat.format(
					JGitText.get().errorOccurredDuringUnpackingOnTheRemoteEnd, unpackStatus));
		}

		for(String refLine : pckIn.readStrings()) {
			boolean ok = false;
			int refNameEnd = -1;
			if(refLine.startsWith("ok ")) {
				ok = true;
				refNameEnd = refLine.length();
			} else if(refLine.startsWith("ng ")) {
				refNameEnd = refLine.indexOf(' ', 3);
			}
			if(refNameEnd == -1)
				throw new PackProtocolException(MessageFormat.format(JGitText.get().unexpectedReportLine2,
						uri, refLine));
			final String refName = refLine.substring(3, refNameEnd);
			final String message = (ok ? null : refLine
					.substring(refNameEnd + 1));

			final RemoteRefUpdate rru = refUpdates.get(refName);
			if(rru == null)
				throw new PackProtocolException(MessageFormat.format(JGitText.get().unexpectedRefReport, uri, refName));
			if(ok) {
				rru.setStatus(Status.OK);
			} else {
				rru.setStatus(Status.REJECTED_OTHER_REASON);
				rru.setMessage(message);
			}
		}
		for(RemoteRefUpdate rru : refUpdates.values()) {
			if(rru.getStatus() == Status.AWAITING_REPORT)
				throw new PackProtocolException(MessageFormat.format(
						JGitText.get().expectedReportForRefNotReceived, uri, rru.getRemoteName()));
		}
	}

	private String readStringLongTimeout() throws IOException {
		if(timeoutIn == null)
			return pckIn.readString();

		final int oldTimeout = timeoutIn.getTimeout();
		final int sendTime = (int) Math.min(packTransferTime, 28800000L);
		try {
			int timeout = 10 * Math.max(sendTime, oldTimeout);
			timeoutIn.setTimeout((timeout < 0) ? Integer.MAX_VALUE : timeout);
			return pckIn.readString();
		} finally {
			timeoutIn.setTimeout(oldTimeout);
		}
	}

	private static class CheckingSideBandOutputStream extends OutputStream {
		private final InputStream in;
		private final OutputStream out;

		CheckingSideBandOutputStream(InputStream in, OutputStream out) {
			this.in = in;
			this.out = out;
		}

		@Override
		public void write(int b) throws IOException {
			write(new byte[] {(byte) b});
		}

		@Override
		public void write(byte[] buf, int ptr, int cnt) throws IOException {
			try {
				out.write(buf, ptr, cnt);
			} catch(IOException e) {
				throw checkError(e);
			}
		}

		@Override
		public void flush() throws IOException {
			try {
				out.flush();
			} catch(IOException e) {
				throw checkError(e);
			}
		}

		private IOException checkError(IOException e1) {
			try {
				in.read();
			} catch(TransportException e2) {
				return e2;
			} catch(IOException e2) {
				return e1;
			}
			return e1;
		}
	}
}
