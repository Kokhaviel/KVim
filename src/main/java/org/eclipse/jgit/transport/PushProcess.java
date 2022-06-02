/*
 * Copyright (C) 2008, 2022 Marek Zawirski <marek.zawirski@gmail.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import java.io.IOException;
import java.io.OutputStream;
import java.text.MessageFormat;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.eclipse.jgit.api.errors.AbortedByHookException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.NotSupportedException;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.hooks.PrePushHook;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RemoteRefUpdate.Status;

class PushProcess {

	static final String PROGRESS_OPENING_CONNECTION = JGitText.get().openingConnection;
	private final Transport transport;
	private PushConnection connection;
	private final Map<String, RemoteRefUpdate> toPush;
	private final RevWalk walker;
	private final OutputStream out;

	private final PrePushHook prePush;

	PushProcess(Transport transport, Collection<RemoteRefUpdate> toPush,
				PrePushHook prePush, OutputStream out) throws TransportException {
		this.walker = new RevWalk(transport.local);
		this.transport = transport;
		this.toPush = new LinkedHashMap<>();
		this.prePush = prePush;
		this.out = out;
		for(RemoteRefUpdate rru : toPush) {
			if(this.toPush.put(rru.getRemoteName(), rru) != null)
				throw new TransportException(MessageFormat.format(
						JGitText.get().duplicateRemoteRefUpdateIsIllegal, rru.getRemoteName()));
		}
	}

	OperationResult execute(ProgressMonitor monitor)
			throws NotSupportedException, TransportException {
		try {
			monitor.beginTask(PROGRESS_OPENING_CONNECTION,
					ProgressMonitor.UNKNOWN);

			final OperationResult res = new OperationResult() {
			};
			connection = transport.openPush();
			try {
				res.setAdvertisedRefs(transport.getURI(), connection
						.getRefsMap());
				res.peerUserAgent = connection.getPeerUserAgent();
				monitor.endTask();

				Map<String, RemoteRefUpdate> expanded = expandMatching();
				toPush.clear();
				toPush.putAll(expanded);

				final Map<String, RemoteRefUpdate> preprocessed = prepareRemoteUpdates();
				List<RemoteRefUpdate> willBeAttempted = preprocessed.values()
						.stream().filter(u -> {
							switch(u.getStatus()) {
								case NON_EXISTING:
								case REJECTED_NODELETE:
								case REJECTED_NONFASTFORWARD:
								case REJECTED_OTHER_REASON:
								case REJECTED_REMOTE_CHANGED:
								case UP_TO_DATE:
									return false;
								default:
									return true;
							}
						}).collect(Collectors.toList());
				if(!willBeAttempted.isEmpty()) {
					if(prePush != null) {
						try {
							prePush.setRefs(willBeAttempted);
							prePush.call();
						} catch(AbortedByHookException | IOException e) {
							throw new TransportException(e.getMessage(), e);
						}
					}
				}
				if(transport.isDryRun())
					modifyUpdatesForDryRun();
				else if(!preprocessed.isEmpty())
					connection.push(monitor, preprocessed, out);
			} finally {
				connection.close();
				res.addMessages(connection.getMessages());
			}
			if(!transport.isDryRun())
				updateTrackingRefs();
			for(RemoteRefUpdate rru : toPush.values()) {
				final TrackingRefUpdate tru = rru.getTrackingRefUpdate();
				if(tru != null)
					res.add(tru);
			}
			return res;
		} finally {
			walker.close();
		}
	}

	private Map<String, RemoteRefUpdate> prepareRemoteUpdates()
			throws TransportException {
		boolean atomic = transport.isPushAtomic();
		final Map<String, RemoteRefUpdate> result = new LinkedHashMap<>();
		for(RemoteRefUpdate rru : toPush.values()) {
			final Ref advertisedRef = connection.getRef(rru.getRemoteName());
			ObjectId advertisedOld = null;
			if(advertisedRef != null) {
				advertisedOld = advertisedRef.getObjectId();
			}
			if(advertisedOld == null) {
				advertisedOld = ObjectId.zeroId();
			}

			if(rru.getNewObjectId().equals(advertisedOld)) {
				if(rru.isDelete()) {
					rru.setStatus(Status.NON_EXISTING);
				} else {
					rru.setStatus(Status.UP_TO_DATE);
				}
				continue;
			}

			if(rru.isExpectingOldObjectId()
					&& !rru.getExpectedOldObjectId().equals(advertisedOld)) {
				rru.setStatus(Status.REJECTED_REMOTE_CHANGED);
				if(atomic) {
					return rejectAll();
				}
				continue;
			}
			if(!rru.isExpectingOldObjectId()) {
				rru.setExpectedOldObjectId(advertisedOld);
			}

			if(advertisedOld.equals(ObjectId.zeroId()) || rru.isDelete()) {
				rru.setFastForward(true);
				result.put(rru.getRemoteName(), rru);
				continue;
			}

			boolean fastForward = isFastForward(advertisedOld,
					rru.getNewObjectId());
			rru.setFastForward(fastForward);
			if(!fastForward && !rru.isForceUpdate()) {
				rru.setStatus(Status.REJECTED_NONFASTFORWARD);
				if(atomic) {
					return rejectAll();
				}
			} else {
				result.put(rru.getRemoteName(), rru);
			}
		}
		return result;
	}

	private boolean isFastForward(ObjectId oldOid, ObjectId newOid)
			throws TransportException {
		try {
			RevObject oldRev = walker.parseAny(oldOid);
			RevObject newRev = walker.parseAny(newOid);
			if(!(oldRev instanceof RevCommit) || !(newRev instanceof RevCommit)
					|| !walker.isMergedInto((RevCommit) oldRev,
					(RevCommit) newRev)) {
				return false;
			}
		} catch(MissingObjectException x) {
			return false;
		} catch(Exception x) {
			throw new TransportException(transport.getURI(),
					MessageFormat.format(JGitText
									.get().readingObjectsFromLocalRepositoryFailed,
							x.getMessage()),
					x);
		}
		return true;
	}

	private Map<String, RemoteRefUpdate> expandMatching()
			throws TransportException {
		Map<String, RemoteRefUpdate> result = new LinkedHashMap<>();
		boolean hadMatch = false;
		for(RemoteRefUpdate update : toPush.values()) {
			if(update.isMatching()) {
				if(hadMatch) {
					throw new TransportException(MessageFormat.format(
							JGitText.get().duplicateRemoteRefUpdateIsIllegal,
							":"));
				}
				expandMatching(result, update);
				hadMatch = true;
			} else if(result.put(update.getRemoteName(), update) != null) {
				throw new TransportException(MessageFormat.format(
						JGitText.get().duplicateRemoteRefUpdateIsIllegal,
						update.getRemoteName()));
			}
		}
		return result;
	}

	private void expandMatching(Map<String, RemoteRefUpdate> updates,
								RemoteRefUpdate match) throws TransportException {
		try {
			Map<String, Ref> advertisement = connection.getRefsMap();
			Collection<RefSpec> fetchSpecs = match.getFetchSpecs();
			boolean forceUpdate = match.isForceUpdate();
			for(Ref local : transport.local.getRefDatabase()
					.getRefsByPrefix(Constants.R_HEADS)) {
				if(local.isSymbolic()) {
					continue;
				}
				String name = local.getName();
				Ref advertised = advertisement.get(name);
				if(advertised == null || advertised.isSymbolic()) {
					continue;
				}
				ObjectId oldOid = advertised.getObjectId();
				if(oldOid == null || ObjectId.zeroId().equals(oldOid)) {
					continue;
				}
				ObjectId newOid = local.getObjectId();
				if(newOid == null || ObjectId.zeroId().equals(newOid)) {
					continue;
				}

				RemoteRefUpdate rru = new RemoteRefUpdate(transport.local, name,
						newOid, name, forceUpdate,
						Transport.findTrackingRefName(name, fetchSpecs),
						oldOid);
				if(updates.put(rru.getRemoteName(), rru) != null) {
					throw new TransportException(MessageFormat.format(
							JGitText.get().duplicateRemoteRefUpdateIsIllegal,
							rru.getRemoteName()));
				}
			}
		} catch(IOException x) {
			throw new TransportException(transport.getURI(),
					MessageFormat.format(JGitText
									.get().readingObjectsFromLocalRepositoryFailed,
							x.getMessage()),
					x);
		}
	}

	private Map<String, RemoteRefUpdate> rejectAll() {
		for(RemoteRefUpdate rru : toPush.values()) {
			if(rru.getStatus() == Status.NOT_ATTEMPTED) {
				rru.setStatus(RemoteRefUpdate.Status.REJECTED_OTHER_REASON);
				rru.setMessage(JGitText.get().transactionAborted);
			}
		}
		return Collections.emptyMap();
	}

	private void modifyUpdatesForDryRun() {
		for(RemoteRefUpdate rru : toPush.values())
			if(rru.getStatus() == Status.NOT_ATTEMPTED)
				rru.setStatus(Status.OK);
	}

	private void updateTrackingRefs() {
		for(RemoteRefUpdate rru : toPush.values()) {
			final Status status = rru.getStatus();
			if(rru.hasTrackingRefUpdate()
					&& (status == Status.UP_TO_DATE || status == Status.OK)) {
				try {
					rru.updateTrackingRef(walker);
				} catch(IOException ignored) {
				}
			}
		}
	}
}
