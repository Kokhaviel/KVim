/*
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

import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.NotSupportedException;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.internal.storage.file.LockFile;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.ObjectWalk;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.jgit.transport.ReceiveCommand.Result.*;
import static org.eclipse.jgit.transport.ReceiveCommand.Type.UPDATE_NONFASTFORWARD;

class FetchProcess {

	private final Transport transport;
	private final Collection<RefSpec> toFetch;
	private final Collection<RefSpec> negativeRefSpecs;
	private final HashMap<ObjectId, Ref> askFor = new HashMap<>();
	private final HashSet<ObjectId> have = new HashSet<>();
	private final ArrayList<TrackingRefUpdate> localUpdates = new ArrayList<>();
	private final ArrayList<FetchHeadRecord> fetchHeadUpdates = new ArrayList<>();
	private final ArrayList<PackLock> packLocks = new ArrayList<>();
	private FetchConnection conn;
	private Map<String, Ref> localRefs;

	FetchProcess(Transport t, Collection<RefSpec> refSpecs) {
		transport = t;
		toFetch = refSpecs.stream().filter(refSpec -> !refSpec.isNegative())
				.collect(Collectors.toList());
		negativeRefSpecs = refSpecs.stream().filter(RefSpec::isNegative)
				.collect(Collectors.toList());
	}

	void execute(ProgressMonitor monitor, FetchResult result,
				 String initialBranch)
			throws NotSupportedException, TransportException {
		askFor.clear();
		localUpdates.clear();
		fetchHeadUpdates.clear();
		packLocks.clear();
		localRefs = null;

		Throwable e1 = null;
		try {
			executeImp(monitor, result, initialBranch);
		} catch(NotSupportedException | TransportException err) {
			e1 = err;
			throw err;
		} finally {
			try {
				for(PackLock lock : packLocks) {
					lock.unlock();
				}
			} catch(IOException e) {
				if(e1 != null) {
					e.addSuppressed(e1);
				}
				throw new TransportException(e.getMessage(), e);
			}
		}
	}

	private boolean isInitialBranchMissing(Map<String, Ref> refsMap,
										   String initialBranch) {
		if(StringUtils.isEmptyOrNull(initialBranch) || refsMap.isEmpty()) {
			return false;
		}
		return !refsMap.containsKey(initialBranch)
				&& !refsMap.containsKey(Constants.R_HEADS + initialBranch)
				&& !refsMap.containsKey(Constants.R_TAGS + initialBranch);
	}

	private void executeImp(final ProgressMonitor monitor,
							final FetchResult result, String initialBranch)
			throws NotSupportedException, TransportException {
		final TagOpt tagopt = transport.getTagOpt();
		String getTags = (tagopt == TagOpt.NO_TAGS) ? null : Constants.R_TAGS;
		String getHead = null;
		try {
			Ref head = transport.local.exactRef(Constants.HEAD);
			ObjectId id = head != null ? head.getObjectId() : null;
			if(id == null || id.equals(ObjectId.zeroId())) {
				getHead = Constants.HEAD;
			}
		} catch(IOException ignored) {
		}
		conn = transport.openFetch(toFetch, getTags, getHead);
		try {
			Map<String, Ref> refsMap = conn.getRefsMap();
			if(isInitialBranchMissing(refsMap, initialBranch)) {
				throw new TransportException(MessageFormat.format(
						JGitText.get().remoteBranchNotFound, initialBranch));
			}
			result.setAdvertisedRefs(transport.getURI(), refsMap);
			result.peerUserAgent = conn.getPeerUserAgent();
			final Set<Ref> matched = new HashSet<>();
			for(RefSpec spec : toFetch) {
				if(spec.getSource() == null)
					throw new TransportException(MessageFormat.format(
							JGitText.get().sourceRefNotSpecifiedForRefspec, spec));

				if(spec.isWildcard())
					expandWildcard(spec, matched);
				else
					expandSingle(spec, matched);
			}

			Collection<Ref> additionalTags = Collections.emptyList();
			if(tagopt == TagOpt.AUTO_FOLLOW)
				additionalTags = expandAutoFollowTags();
			else if(tagopt == TagOpt.FETCH_TAGS)
				expandFetchTags();

			final boolean includedTags;
			if(!askFor.isEmpty() && !askForIsComplete()) {
				fetchObjects(monitor);
				includedTags = conn.didFetchIncludeTags();

				closeConnection(result);
			} else {
				includedTags = false;
			}

			if(tagopt == TagOpt.AUTO_FOLLOW && !additionalTags.isEmpty()) {
				have.addAll(askFor.keySet());
				askFor.clear();
				for(Ref r : additionalTags) {
					ObjectId id = r.getPeeledObjectId();
					if(id == null)
						id = r.getObjectId();
					if(localHasObject(id))
						wantTag(r);
				}

				if(!askFor.isEmpty() && (!includedTags || !askForIsComplete())) {
					reopenConnection();
					if(!askFor.isEmpty())
						fetchObjects(monitor);
				}
			}
		} finally {
			closeConnection(result);
		}

		BatchRefUpdate batch = transport.local.getRefDatabase()
				.newBatchUpdate()
				.setAllowNonFastForwards(true)
				.setRefLogMessage("fetch", true);
		try(RevWalk walk = new RevWalk(transport.local)) {
			walk.setRetainBody(false);
			if(monitor instanceof BatchingProgressMonitor) {
				((BatchingProgressMonitor) monitor).setDelayStart(
						250, TimeUnit.MILLISECONDS);
			}
			if(transport.isRemoveDeletedRefs()) {
				deleteStaleTrackingRefs(result, batch);
			}
			addUpdateBatchCommands(result, batch);
			for(ReceiveCommand cmd : batch.getCommands()) {
				cmd.updateType(walk);
				if(cmd.getType() == UPDATE_NONFASTFORWARD
						&& cmd instanceof TrackingRefUpdate.Command
						&& !((TrackingRefUpdate.Command) cmd).canForceUpdate())
					cmd.setResult(REJECTED_NONFASTFORWARD);
			}
			if(transport.isDryRun()) {
				for(ReceiveCommand cmd : batch.getCommands()) {
					if(cmd.getResult() == NOT_ATTEMPTED)
						cmd.setResult(OK);
				}
			} else {
				batch.execute(walk, monitor);
			}
		} catch(TransportException e) {
			throw e;
		} catch(IOException err) {
			throw new TransportException(MessageFormat.format(
					JGitText.get().failureUpdatingTrackingRef,
					getFirstFailedRefName(batch), err.getMessage()), err);
		}

		if(!fetchHeadUpdates.isEmpty()) {
			try {
				updateFETCH_HEAD(result);
			} catch(IOException err) {
				throw new TransportException(MessageFormat.format(
						JGitText.get().failureUpdatingFETCH_HEAD, err.getMessage()), err);
			}
		}
	}

	private void addUpdateBatchCommands(FetchResult result,
										BatchRefUpdate batch) throws TransportException {
		Map<String, ObjectId> refs = new HashMap<>();
		for(TrackingRefUpdate u : localUpdates) {
			ObjectId existing = refs.get(u.getLocalName());
			if(existing == null) {
				refs.put(u.getLocalName(), u.getNewObjectId());
				result.add(u);
				batch.addCommand(u.asReceiveCommand());
			} else if(!existing.equals(u.getNewObjectId())) {
				throw new TransportException(MessageFormat
						.format(JGitText.get().duplicateRef, u.getLocalName()));
			}
		}
	}

	private void fetchObjects(ProgressMonitor monitor)
			throws TransportException {
		try {
			conn.setPackLockMessage("jgit fetch " + transport.uri);
			conn.fetch(monitor, askFor.values(), have);
		} finally {
			packLocks.addAll(conn.getPackLocks());
		}
		if(transport.isCheckFetchedObjects()
				&& !conn.didFetchTestConnectivity() && !askForIsComplete())
			throw new TransportException(transport.getURI(),
					JGitText.get().peerDidNotSupplyACompleteObjectGraph);
	}

	private void closeConnection(FetchResult result) {
		if(conn != null) {
			conn.close();
			result.addMessages(conn.getMessages());
			conn = null;
		}
	}

	private void reopenConnection() throws NotSupportedException,
			TransportException {
		if(conn != null)
			return;

		Set<String> prefixes = new HashSet<>();
		for(Ref toGet : askFor.values()) {
			String src = toGet.getName();
			prefixes.add(src);
			prefixes.add(Constants.R_REFS + src);
			prefixes.add(Constants.R_HEADS + src);
			prefixes.add(Constants.R_TAGS + src);
		}
		conn = transport.openFetch(Collections.emptyList(),
				prefixes.toArray(new String[0]));

		final HashMap<ObjectId, Ref> avail = new HashMap<>();
		for(Ref r : conn.getRefs())
			avail.put(r.getObjectId(), r);

		final Collection<Ref> wants = new ArrayList<>(askFor.values());
		askFor.clear();
		for(Ref want : wants) {
			final Ref newRef = avail.get(want.getObjectId());
			if(newRef != null) {
				askFor.put(newRef.getObjectId(), newRef);
			} else {
				removeFetchHeadRecord(want.getObjectId());
				removeTrackingRefUpdate(want.getObjectId());
			}
		}
	}

	private void removeTrackingRefUpdate(ObjectId want) {
		localUpdates.removeIf(u -> u.getNewObjectId().equals(want));
	}

	private void removeFetchHeadRecord(ObjectId want) {
		fetchHeadUpdates.removeIf(fh -> fh.newValue.equals(want));
	}

	private void updateFETCH_HEAD(FetchResult result) throws IOException {
		File meta = transport.local.getDirectory();
		if(meta == null)
			return;
		final LockFile lock = new LockFile(new File(meta, "FETCH_HEAD"));
		try {
			if(lock.lock()) {
				try(Writer w = new OutputStreamWriter(
						lock.getOutputStream(), UTF_8)) {
					for(FetchHeadRecord h : fetchHeadUpdates) {
						h.write(w);
						result.add(h);
					}
				}
				lock.commit();
			}
		} finally {
			lock.unlock();
		}
	}

	private boolean askForIsComplete() throws TransportException {
		try {
			try(ObjectWalk ow = new ObjectWalk(transport.local)) {
				for(ObjectId want : askFor.keySet())
					ow.markStart(ow.parseAny(want));
				for(Ref ref : localRefs().values())
					ow.markUninteresting(ow.parseAny(ref.getObjectId()));
				ow.checkConnectivity();
			}
			return true;
		} catch(MissingObjectException e) {
			return false;
		} catch(IOException e) {
			throw new TransportException(JGitText.get().unableToCheckConnectivity, e);
		}
	}

	private void expandWildcard(RefSpec spec, Set<Ref> matched)
			throws TransportException {
		for(Ref src : conn.getRefs()) {
			if(spec.matchSource(src)) {
				RefSpec expandedRefSpec = spec.expandFromSource(src);
				if(!matchNegativeRefSpec(expandedRefSpec)
						&& matched.add(src)) {
					want(src, expandedRefSpec);
				}
			}
		}
	}

	private void expandSingle(RefSpec spec, Set<Ref> matched)
			throws TransportException {
		String want = spec.getSource();
		if(ObjectId.isId(want)) {
			want(ObjectId.fromString(want));
			return;
		}

		Ref src = conn.getRef(want);
		if(src == null) {
			throw new TransportException(MessageFormat.format(JGitText.get().remoteDoesNotHaveSpec, want));
		}
		if(!matchNegativeRefSpec(spec) && matched.add(src)) {
			want(src, spec);
		}
	}

	private boolean matchNegativeRefSpec(RefSpec spec) {
		for(RefSpec negativeRefSpec : negativeRefSpecs) {
			if(negativeRefSpec.getSource() != null && spec.getSource() != null
					&& negativeRefSpec.matchSource(spec.getSource())) {
				return true;
			}

			if(negativeRefSpec.getDestination() != null
					&& spec.getDestination() != null && negativeRefSpec
					.matchDestination(spec.getDestination())) {
				return true;
			}
		}
		return false;
	}

	private boolean localHasObject(ObjectId id) throws TransportException {
		try {
			return transport.local.getObjectDatabase().has(id);
		} catch(IOException err) {
			throw new TransportException(
					MessageFormat.format(
							JGitText.get().readingObjectsFromLocalRepositoryFailed,
							err.getMessage()),
					err);
		}
	}

	private Collection<Ref> expandAutoFollowTags() throws TransportException {
		final Collection<Ref> additionalTags = new ArrayList<>();
		final Map<String, Ref> haveRefs = localRefs();
		for(Ref r : conn.getRefs()) {
			if(!isTag(r))
				continue;

			Ref local = haveRefs.get(r.getName());
			if(local != null)
				continue;

			ObjectId obj = r.getPeeledObjectId();
			if(obj == null)
				obj = r.getObjectId();

			if(askFor.containsKey(obj) || localHasObject(obj))
				wantTag(r);
			else
				additionalTags.add(r);
		}
		return additionalTags;
	}

	private void expandFetchTags() throws TransportException {
		final Map<String, Ref> haveRefs = localRefs();
		for(Ref r : conn.getRefs()) {
			if(!isTag(r)) {
				continue;
			}
			ObjectId id = r.getObjectId();
			if(id == null) {
				continue;
			}
			final Ref local = haveRefs.get(r.getName());
			if(local == null || !id.equals(local.getObjectId())) {
				wantTag(r);
			}
		}
	}

	private void wantTag(Ref r) throws TransportException {
		want(r, new RefSpec().setSource(r.getName())
				.setDestination(r.getName()).setForceUpdate(true));
	}

	private void want(Ref src, RefSpec spec)
			throws TransportException {
		final ObjectId newId = src.getObjectId();
		if(newId == null) {
			throw new NullPointerException(MessageFormat.format(
					JGitText.get().transportProvidedRefWithNoObjectId,
					src.getName()));
		}
		if(spec.getDestination() != null) {
			final TrackingRefUpdate tru = createUpdate(spec, newId);
			if(newId.equals(tru.getOldObjectId()))
				return;
			localUpdates.add(tru);
		}

		askFor.put(newId, src);

		final FetchHeadRecord fhr = new FetchHeadRecord();
		fhr.newValue = newId;
		fhr.notForMerge = spec.getDestination() != null;
		fhr.sourceName = src.getName();
		fhr.sourceURI = transport.getURI();
		fetchHeadUpdates.add(fhr);
	}

	private void want(ObjectId id) {
		askFor.put(id,
				new ObjectIdRef.Unpeeled(Ref.Storage.NETWORK, id.name(), id));
	}

	private TrackingRefUpdate createUpdate(RefSpec spec, ObjectId newId)
			throws TransportException {
		Ref ref = localRefs().get(spec.getDestination());
		ObjectId oldId = ref != null && ref.getObjectId() != null
				? ref.getObjectId()
				: ObjectId.zeroId();
		return new TrackingRefUpdate(
				spec.isForceUpdate(),
				spec.getSource(),
				spec.getDestination(),
				oldId,
				newId);
	}

	private Map<String, Ref> localRefs() throws TransportException {
		if(localRefs == null) {
			try {
				localRefs = transport.local.getRefDatabase()
						.getRefs(RefDatabase.ALL);
			} catch(IOException err) {
				throw new TransportException(JGitText.get().cannotListRefs, err);
			}
		}
		return localRefs;
	}

	private void deleteStaleTrackingRefs(FetchResult result,
										 BatchRefUpdate batch) throws IOException {
		Set<Ref> processed = new HashSet<>();
		for(Ref ref : localRefs().values()) {
			if(ref.isSymbolic()) {
				continue;
			}
			String refname = ref.getName();
			for(RefSpec spec : toFetch) {
				if(spec.matchDestination(refname)) {
					RefSpec s = spec.expandFromDestination(refname);
					if(result.getAdvertisedRef(s.getSource()) == null
							&& processed.add(ref)) {
						deleteTrackingRef(result, batch, s, ref);
					}
				}
			}
		}
	}

	private void deleteTrackingRef(final FetchResult result,
								   final BatchRefUpdate batch, final RefSpec spec, final Ref localRef) {
		if(localRef.getObjectId() == null)
			return;
		TrackingRefUpdate update = new TrackingRefUpdate(
				true,
				spec.getSource(),
				localRef.getName(),
				localRef.getObjectId(),
				ObjectId.zeroId());
		result.add(update);
		batch.addCommand(update.asReceiveCommand());
	}

	private static boolean isTag(Ref r) {
		return isTag(r.getName());
	}

	private static boolean isTag(String name) {
		return name.startsWith(Constants.R_TAGS);
	}

	private static String getFirstFailedRefName(BatchRefUpdate batch) {
		for(ReceiveCommand cmd : batch.getCommands()) {
			if(cmd.getResult() != ReceiveCommand.Result.OK)
				return cmd.getRefName();
		}
		return "";
	}
}
