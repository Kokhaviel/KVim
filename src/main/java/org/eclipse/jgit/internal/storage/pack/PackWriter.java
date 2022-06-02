/*
 * Copyright (C) 2008-2010, Google Inc.
 * Copyright (C) 2008, Marek Zawirski <marek.zawirski@gmail.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.pack;

import org.eclipse.jgit.annotations.*;
import org.eclipse.jgit.errors.*;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.internal.storage.file.*;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.lib.BitmapIndex.*;
import org.eclipse.jgit.revwalk.*;
import org.eclipse.jgit.storage.pack.*;
import org.eclipse.jgit.transport.*;
import org.eclipse.jgit.util.BlockList;
import org.eclipse.jgit.util.TemporaryBuffer;

import java.io.*;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.text.MessageFormat;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.CRC32;
import java.util.zip.*;

import static java.util.Objects.*;
import static org.eclipse.jgit.internal.storage.pack.StoredObjectRepresentation.*;
import static org.eclipse.jgit.lib.Constants.*;

public class PackWriter implements AutoCloseable {
	private static final int PACK_VERSION_GENERATED = 2;
	public static final Set<ObjectId> NONE = Collections.emptySet();
	private static final Map<WeakReference<PackWriter>, Boolean> instances = new ConcurrentHashMap<>();

	@SuppressWarnings("unchecked")
	BlockList<ObjectToPack>[] objectsLists = new BlockList[OBJ_TAG + 1];

	{
		objectsLists[OBJ_COMMIT] = new BlockList<>();
		objectsLists[OBJ_TREE] = new BlockList<>();
		objectsLists[OBJ_BLOB] = new BlockList<>();
		objectsLists[OBJ_TAG] = new BlockList<>();
	}

	private ObjectIdOwnerMap<ObjectToPack> objectsMap = new ObjectIdOwnerMap<>();
	private final List<ObjectToPack> edgeObjects = new BlockList<>();
	private BitmapBuilder haveObjects;
	private final List<CachedPack> cachedPacks = new ArrayList<>(2);
	private Set<ObjectId> tagTargets = NONE;
	private Set<? extends ObjectId> excludeFromBitmapSelection = NONE;
	private ObjectIdSet[] excludeInPacks;
	private ObjectIdSet excludeInPackLast;
	private Deflater myDeflater;
	private final ObjectReader reader;
	private final ObjectReuseAsIs reuseSupport;
	final PackConfig config;
	private final PackStatistics.Accumulator stats;
	private final MutableState state;
	private final WeakReference<PackWriter> selfRef;
	private PackStatistics.ObjectType.Accumulator typeStats;
	private List<ObjectToPack> sortedByName;
	private byte[] packcsum;
	private boolean deltaBaseAsOffset, reuseDeltas, reuseDeltaCommits, reuseValidate, thin, useCachedPacks,
			useBitmaps, pruneCurrentObjectList, shallowPack, canBuildBitmaps, indexDisabled, checkSearchForReuseTimeout = false;
	private final boolean ignoreMissingUninteresting = true;
	private final Duration searchForReuseTimeout;
	private long searchForReuseStartTimeEpoc;
	private int depth;
	private Collection<? extends ObjectId> unshallowObjects;
	private PackBitmapIndexBuilder writeBitmaps;
	private CRC32 crc32;
	private ObjectCountCallback callback;
	private FilterSpec filterSpec = FilterSpec.NO_FILTER;
	private PackfileUriConfig packfileUriConfig;

	public PackWriter(PackConfig config, ObjectReader reader) {
		this(config, reader, null);
	}

	public PackWriter(PackConfig config, final ObjectReader reader,
					  @Nullable PackStatistics.Accumulator statsAccumulator) {
		this.config = config;
		this.reader = reader;
		if(reader instanceof ObjectReuseAsIs) reuseSupport = ((ObjectReuseAsIs) reader);
		else reuseSupport = null;

		deltaBaseAsOffset = config.isDeltaBaseAsOffset();
		reuseDeltas = config.isReuseDeltas();
		searchForReuseTimeout = config.getSearchForReuseTimeout();
		reuseValidate = true;
		stats = statsAccumulator != null ? statsAccumulator : new PackStatistics.Accumulator();
		state = new MutableState();
		selfRef = new WeakReference<>(this);
		instances.put(selfRef, Boolean.TRUE);
	}

	public void setClientShallowCommits(Set<ObjectId> clientShallowCommits) {
		stats.clientShallowCommits = Collections.unmodifiableSet(new HashSet<>(clientShallowCommits));
	}

	public boolean isDeltaBaseAsOffset() {
		return deltaBaseAsOffset;
	}

	public void checkSearchForReuseTimeout() throws SearchForReuseTimeout {
		if(checkSearchForReuseTimeout && Duration.ofMillis(System.currentTimeMillis()
				- searchForReuseStartTimeEpoc).compareTo(searchForReuseTimeout) > 0) {
			throw new SearchForReuseTimeout(searchForReuseTimeout);
		}
	}

	public void setDeltaBaseAsOffset(boolean deltaBaseAsOffset) {
		this.deltaBaseAsOffset = deltaBaseAsOffset;
	}

	public void enableSearchForReuseTimeout() {
		this.checkSearchForReuseTimeout = true;
	}

	public void setReuseDeltaCommits(boolean reuse) {
		reuseDeltaCommits = reuse;
	}

	public void setReuseValidatingObjects(boolean validate) {
		reuseValidate = validate;
	}

	public void setThin(boolean packthin) {
		thin = packthin;
	}

	public void setUseCachedPacks(boolean useCached) {
		useCachedPacks = useCached;
	}

	public void setUseBitmaps(boolean useBitmaps) {
		this.useBitmaps = useBitmaps;
	}

	public boolean isIndexDisabled() {
		return indexDisabled || !cachedPacks.isEmpty();
	}

	public void setIndexDisabled(boolean noIndex) {
		this.indexDisabled = noIndex;
	}

	public void setTagTargets(Set<ObjectId> objects) {
		tagTargets = objects;
	}

	public void setShallowPack(int depth, Collection<? extends ObjectId> unshallow) {
		this.shallowPack = true;
		this.depth = depth;
		this.unshallowObjects = unshallow;
	}

	public void setFilterSpec(@NonNull FilterSpec filter) {
		filterSpec = requireNonNull(filter);
	}

	public void setPackfileUriConfig(PackfileUriConfig config) {
		packfileUriConfig = config;
	}

	public long getObjectCount() throws IOException {
		if(stats.totalObjects == 0) {
			long objCnt = 0;

			objCnt += objectsLists[OBJ_COMMIT].size();
			objCnt += objectsLists[OBJ_TREE].size();
			objCnt += objectsLists[OBJ_BLOB].size();
			objCnt += objectsLists[OBJ_TAG].size();

			for(CachedPack pack : cachedPacks) objCnt += pack.getObjectCount();
			return objCnt;
		}
		return stats.totalObjects;
	}

	private long getUnoffloadedObjectCount() throws IOException {
		long objCnt = 0;

		objCnt += objectsLists[OBJ_COMMIT].size();
		objCnt += objectsLists[OBJ_TREE].size();
		objCnt += objectsLists[OBJ_BLOB].size();
		objCnt += objectsLists[OBJ_TAG].size();

		for(CachedPack pack : cachedPacks) {
			CachedPackUriProvider.PackInfo packInfo = packfileUriConfig.cachedPackUriProvider.getInfo(
					pack, packfileUriConfig.protocolsSupported);
			if(packInfo == null) objCnt += pack.getObjectCount();
		}
		return objCnt;
	}

	public void excludeObjects(ObjectIdSet idx) {
		if(excludeInPacks == null) {
			excludeInPacks = new ObjectIdSet[] {idx};
			excludeInPackLast = idx;
		} else {
			int cnt = excludeInPacks.length;
			ObjectIdSet[] newList = new ObjectIdSet[cnt + 1];
			System.arraycopy(excludeInPacks, 0, newList, 0, cnt);
			newList[cnt] = idx;
			excludeInPacks = newList;
		}
	}

	public void preparePack(ProgressMonitor countingMonitor, @NonNull Set<? extends ObjectId> want,
							@NonNull Set<? extends ObjectId> have) throws IOException {
		preparePack(countingMonitor, want, have, NONE, NONE);
	}

	public void preparePack(ProgressMonitor countingMonitor,
							@NonNull Set<? extends ObjectId> want, @NonNull Set<? extends ObjectId> have,
							@NonNull Set<? extends ObjectId> shallow) throws IOException {
		preparePack(countingMonitor, want, have, shallow, NONE);
	}

	public void preparePack(ProgressMonitor countingMonitor, @NonNull Set<? extends ObjectId> want,
							@NonNull Set<? extends ObjectId> have, @NonNull Set<? extends ObjectId> shallow,
							@NonNull Set<? extends ObjectId> noBitmaps) throws IOException {
		try(ObjectWalk ow = getObjectWalk()) {
			ow.assumeShallow(shallow);
			preparePack(countingMonitor, ow, want, have, noBitmaps);
		}
	}

	private ObjectWalk getObjectWalk() {
		return shallowPack ? new DepthWalk.ObjectWalk(reader, depth - 1) : new ObjectWalk(reader);
	}

	private static class DepthAwareVisitationPolicy implements ObjectWalk.VisitationPolicy {
		private final Map<ObjectId, Integer> lowestDepthVisited = new HashMap<>();
		private final ObjectWalk walk;

		DepthAwareVisitationPolicy(ObjectWalk walk) {
			this.walk = requireNonNull(walk);
		}

		@Override
		public boolean shouldVisit(RevObject o) {
			Integer lastDepth = lowestDepthVisited.get(o);
			if(lastDepth == null) {
				return true;
			}
			return walk.getTreeDepth() < lastDepth;
		}

		@Override
		public void visited(RevObject o) {
			lowestDepthVisited.put(o, walk.getTreeDepth());
		}
	}

	public void preparePack(ProgressMonitor countingMonitor, @NonNull ObjectWalk walk,
							@NonNull Set<? extends ObjectId> interestingObjects,
							@NonNull Set<? extends ObjectId> uninterestingObjects,
							@NonNull Set<? extends ObjectId> noBitmaps) throws IOException {
		if(countingMonitor == null) countingMonitor = NullProgressMonitor.INSTANCE;
		if(shallowPack && !(walk instanceof DepthWalk.ObjectWalk))
			throw new IllegalArgumentException(JGitText.get().shallowPacksRequireDepthWalk);
		if(filterSpec.getTreeDepthLimit() >= 0) walk.setVisitationPolicy(new DepthAwareVisitationPolicy(walk));
		findObjectsToPack(countingMonitor, walk, interestingObjects, uninterestingObjects, noBitmaps);
	}

	public boolean willInclude(AnyObjectId id) {
		ObjectToPack obj = objectsMap.get(id);
		return obj != null && !obj.isEdge();
	}

	public ObjectToPack get(AnyObjectId id) {
		ObjectToPack obj = objectsMap.get(id);
		return obj != null && !obj.isEdge() ? obj : null;
	}

	public ObjectId computeName() {
		final byte[] buf = new byte[OBJECT_ID_LENGTH];
		final MessageDigest md = Constants.newMessageDigest();
		for(ObjectToPack otp : sortByName()) {
			otp.copyRawTo(buf, 0);
			md.update(buf, 0, OBJECT_ID_LENGTH);
		}
		return ObjectId.fromRaw(md.digest());
	}

	public int getIndexVersion() {
		int indexVersion = config.getIndexVersion();
		if(indexVersion <= 0) for(BlockList<ObjectToPack> objs : objectsLists)
			indexVersion =
					Math.max(indexVersion, PackIndexWriter.oldestPossibleFormat(objs));

		return indexVersion;
	}

	public void writeIndex(OutputStream indexStream) throws IOException {
		if(isIndexDisabled()) throw new IOException(JGitText.get().cachedPacksPreventsIndexCreation);

		long writeStart = System.currentTimeMillis();
		final PackIndexWriter iw = PackIndexWriter.createVersion(indexStream, getIndexVersion());
		iw.write(sortByName(), packcsum);
		stats.timeWriting += System.currentTimeMillis() - writeStart;
	}

	public void writeBitmapIndex(OutputStream bitmapIndexStream) throws IOException {
		if(writeBitmaps == null) throw new IOException(JGitText.get().bitmapsMustBePrepared);

		long writeStart = System.currentTimeMillis();
		final PackBitmapIndexWriterV1 iw = new PackBitmapIndexWriterV1(bitmapIndexStream);
		iw.write(writeBitmaps, packcsum);
		stats.timeWriting += System.currentTimeMillis() - writeStart;
	}

	private List<ObjectToPack> sortByName() {
		if(sortedByName == null) {
			int cnt = 0;
			cnt += objectsLists[OBJ_COMMIT].size();
			cnt += objectsLists[OBJ_TREE].size();
			cnt += objectsLists[OBJ_BLOB].size();
			cnt += objectsLists[OBJ_TAG].size();

			sortedByName = new BlockList<>(cnt);
			sortedByName.addAll(objectsLists[OBJ_COMMIT]);
			sortedByName.addAll(objectsLists[OBJ_TREE]);
			sortedByName.addAll(objectsLists[OBJ_BLOB]);
			sortedByName.addAll(objectsLists[OBJ_TAG]);
			Collections.sort(sortedByName);
		}
		return sortedByName;
	}

	private void beginPhase(PackingPhase phase, ProgressMonitor monitor,
							long cnt) {
		state.phase = phase;
		String task;
		switch(phase) {
			case COUNTING:
				task = JGitText.get().countingObjects;
				break;
			case GETTING_SIZES:
				task = JGitText.get().searchForSizes;
				break;
			case FINDING_SOURCES:
				task = JGitText.get().searchForReuse;
				break;
			case COMPRESSING:
				task = JGitText.get().compressingObjects;
				break;
			case WRITING:
				task = JGitText.get().writingObjects;
				break;
			case BUILDING_BITMAPS:
				task = JGitText.get().buildingBitmaps;
				break;
			default:
				throw new IllegalArgumentException(
						MessageFormat.format(JGitText.get().illegalPackingPhase, phase));
		}
		monitor.beginTask(task, (int) cnt);
	}

	private void endPhase(ProgressMonitor monitor) {
		monitor.endTask();
	}

	public void writePack(ProgressMonitor compressMonitor,
						  ProgressMonitor writeMonitor, OutputStream packStream) throws IOException {
		if(compressMonitor == null) compressMonitor = NullProgressMonitor.INSTANCE;
		if(writeMonitor == null) writeMonitor = NullProgressMonitor.INSTANCE;

		excludeInPacks = null;
		excludeInPackLast = null;

		boolean needSearchForReuse = reuseSupport != null && (reuseDeltas || config.isReuseObjects() || !cachedPacks.isEmpty());

		if(compressMonitor instanceof BatchingProgressMonitor) {
			long delay = 1000;
			if(needSearchForReuse && config.isDeltaCompress()) delay = 500;
			((BatchingProgressMonitor) compressMonitor).setDelayStart(delay, TimeUnit.MILLISECONDS);
		}

		if(needSearchForReuse) searchForReuse(compressMonitor);
		if(config.isDeltaCompress()) searchForDeltas(compressMonitor);

		crc32 = new CRC32();
		final PackOutputStream out = new PackOutputStream(writeMonitor, isIndexDisabled() ? packStream
				: new CheckedOutputStream(packStream, crc32), this);

		long objCnt = packfileUriConfig == null ? getObjectCount() : getUnoffloadedObjectCount();
		stats.totalObjects = objCnt;
		if(callback != null) callback.setObjectCount(objCnt);
		beginPhase(PackingPhase.WRITING, writeMonitor, objCnt);
		long writeStart = System.currentTimeMillis();
		try {
			List<CachedPack> unwrittenCachedPacks;

			if(packfileUriConfig != null) {
				unwrittenCachedPacks = new ArrayList<>();
				CachedPackUriProvider p = packfileUriConfig.cachedPackUriProvider;
				PacketLineOut o = packfileUriConfig.pckOut;

				o.writeString("packfile-uris\n");
				for(CachedPack pack : cachedPacks) {
					CachedPackUriProvider.PackInfo packInfo = p.getInfo(pack, packfileUriConfig.protocolsSupported);
					if(packInfo != null) {
						o.writeString(packInfo.getHash() + ' ' + packInfo.getUri() + '\n');
						stats.offloadedPackfiles += 1;
						stats.offloadedPackfileSize += packInfo.getSize();
					} else unwrittenCachedPacks.add(pack);
				}
				packfileUriConfig.pckOut.writeDelim();
				packfileUriConfig.pckOut.writeString("packfile\n");
			} else {
				unwrittenCachedPacks = cachedPacks;
			}

			out.writeFileHeader(PACK_VERSION_GENERATED, objCnt);
			out.flush();

			writeObjects(out);
			if(!edgeObjects.isEmpty() || !cachedPacks.isEmpty()) {
				for(PackStatistics.ObjectType.Accumulator typeStat : stats.objectTypes) {
					if(typeStat == null)
						continue;
					stats.thinPackBytes += typeStat.bytes;
				}
			}

			stats.reusedPacks = Collections.unmodifiableList(cachedPacks);
			for(CachedPack pack : unwrittenCachedPacks) {
				long deltaCnt = pack.getDeltaCount();
				stats.reusedObjects += pack.getObjectCount();
				stats.reusedDeltas += deltaCnt;
				stats.totalDeltas += deltaCnt;
				reuseSupport.copyPackAsIs(out, pack);
			}
			writeChecksum(out);
			out.flush();
		} finally {
			stats.timeWriting = System.currentTimeMillis() - writeStart;
			stats.depth = depth;

			for(PackStatistics.ObjectType.Accumulator typeStat : stats.objectTypes) {
				if(typeStat == null)
					continue;
				typeStat.cntDeltas += typeStat.reusedDeltas;
				stats.reusedObjects += typeStat.reusedObjects;
				stats.reusedDeltas += typeStat.reusedDeltas;
				stats.totalDeltas += typeStat.cntDeltas;
			}
		}

		stats.totalBytes = out.length();
		reader.close();
		endPhase(writeMonitor);
	}

	public PackStatistics getStatistics() {
		return new PackStatistics(stats);
	}

	public State getState() {
		return state.snapshot();
	}

	@Override
	public void close() {
		reader.close();
		if(myDeflater != null) {
			myDeflater.end();
			myDeflater = null;
		}
		instances.remove(selfRef);
	}

	private void searchForReuse(ProgressMonitor monitor) throws IOException {
		long cnt = 0;
		cnt += objectsLists[OBJ_COMMIT].size();
		cnt += objectsLists[OBJ_TREE].size();
		cnt += objectsLists[OBJ_BLOB].size();
		cnt += objectsLists[OBJ_TAG].size();

		long start = System.currentTimeMillis();
		searchForReuseStartTimeEpoc = start;
		beginPhase(PackingPhase.FINDING_SOURCES, monitor, cnt);
		if(cnt <= 4096) {
			BlockList<ObjectToPack> tmp = new BlockList<>((int) cnt);
			tmp.addAll(objectsLists[OBJ_TAG]);
			tmp.addAll(objectsLists[OBJ_COMMIT]);
			tmp.addAll(objectsLists[OBJ_TREE]);
			tmp.addAll(objectsLists[OBJ_BLOB]);
			searchForReuse(monitor, tmp);
			if(pruneCurrentObjectList) {
				pruneEdgesFromObjectList(objectsLists[OBJ_COMMIT]);
				pruneEdgesFromObjectList(objectsLists[OBJ_TREE]);
				pruneEdgesFromObjectList(objectsLists[OBJ_BLOB]);
				pruneEdgesFromObjectList(objectsLists[OBJ_TAG]);
			}
		} else {
			searchForReuse(monitor, objectsLists[OBJ_TAG]);
			searchForReuse(monitor, objectsLists[OBJ_COMMIT]);
			searchForReuse(monitor, objectsLists[OBJ_TREE]);
			searchForReuse(monitor, objectsLists[OBJ_BLOB]);
		}
		endPhase(monitor);
		stats.timeSearchingForReuse = System.currentTimeMillis() - start;

		if(config.isReuseDeltas() && config.getCutDeltaChains()) {
			cutDeltaChains(objectsLists[OBJ_TREE]);
			cutDeltaChains(objectsLists[OBJ_BLOB]);
		}
	}

	private void searchForReuse(ProgressMonitor monitor, List<ObjectToPack> list)
			throws IOException {
		pruneCurrentObjectList = false;
		reuseSupport.selectObjectRepresentation(this, monitor, list);
		if(pruneCurrentObjectList)
			pruneEdgesFromObjectList(list);
	}

	private void cutDeltaChains(BlockList<ObjectToPack> list)
			throws IOException {
		int max = config.getMaxDeltaDepth();
		for(int idx = list.size() - 1; idx >= 0; idx--) {
			int d = 0;
			ObjectToPack b = list.get(idx).getDeltaBase();
			while(b != null) {
				if(d < b.getChainLength())
					break;
				b.setChainLength(++d);
				if(d >= max && b.isDeltaRepresentation()) {
					reselectNonDelta(b);
					break;
				}
				b = b.getDeltaBase();
			}
		}
		if(config.isDeltaCompress()) {
			for(ObjectToPack otp : list)
				otp.clearChainLength();
		}
	}

	private void searchForDeltas(ProgressMonitor monitor) throws IOException {
		ObjectToPack[] list = new ObjectToPack[
				objectsLists[OBJ_TREE].size()
						+ objectsLists[OBJ_BLOB].size()
						+ edgeObjects.size()];
		int cnt = 0;
		cnt = findObjectsNeedingDelta(list, cnt, OBJ_TREE);
		cnt = findObjectsNeedingDelta(list, cnt, OBJ_BLOB);
		if(cnt == 0)
			return;
		int nonEdgeCnt = cnt;

		for(ObjectToPack eo : edgeObjects) {
			eo.setWeight(0);
			list[cnt++] = eo;
		}

		final long sizingStart = System.currentTimeMillis();
		beginPhase(PackingPhase.GETTING_SIZES, monitor, cnt);
		AsyncObjectSizeQueue<ObjectToPack> sizeQueue = reader.getObjectSize(
				Arrays.asList(list).subList(0, cnt), false);
		try {
			final long limit = config.getBigFileThreshold();
			for(; ; ) {
				try {
					if(!sizeQueue.next())
						break;
				} catch(MissingObjectException notFound) {
					monitor.update(1);
					if(ignoreMissingUninteresting) {
						ObjectToPack otp = sizeQueue.getCurrent();
						if(otp != null && otp.isEdge()) {
							otp.setDoNotDelta();
							continue;
						}

						otp = objectsMap.get(notFound.getObjectId());
						if(otp != null && otp.isEdge()) {
							otp.setDoNotDelta();
							continue;
						}
					}
					throw notFound;
				}

				ObjectToPack otp = sizeQueue.getCurrent();
				if(otp == null)
					otp = objectsMap.get(sizeQueue.getObjectId());

				long sz = sizeQueue.getSize();
				if(DeltaIndex.BLKSZ < sz && sz < limit)
					otp.setWeight((int) sz);
				else
					otp.setDoNotDelta();
				monitor.update(1);
			}
		} finally {
			sizeQueue.release();
		}
		endPhase(monitor);
		stats.timeSearchingForSizes = System.currentTimeMillis() - sizingStart;

		Arrays.sort(list, 0, cnt, (ObjectToPack a, ObjectToPack b) -> {
			int cmp = (a.isDoNotDelta() ? 1 : 0) - (b.isDoNotDelta() ? 1 : 0);
			if(cmp != 0) {
				return cmp;
			}

			cmp = a.getType() - b.getType();
			if(cmp != 0) {
				return cmp;
			}

			cmp = (a.getPathHash() >>> 1) - (b.getPathHash() >>> 1);
			if(cmp != 0) {
				return cmp;
			}

			cmp = (a.getPathHash() & 1) - (b.getPathHash() & 1);
			if(cmp != 0) {
				return cmp;
			}

			cmp = (a.isEdge() ? 0 : 1) - (b.isEdge() ? 0 : 1);
			if(cmp != 0) {
				return cmp;
			}

			return b.getWeight() - a.getWeight();
		});

		while(0 < cnt && list[cnt - 1].isDoNotDelta()) {
			if(!list[cnt - 1].isEdge())
				nonEdgeCnt--;
			cnt--;
		}
		if(cnt == 0)
			return;

		final long searchStart = System.currentTimeMillis();
		searchForDeltas(monitor, list, cnt);
		stats.deltaSearchNonEdgeObjects = nonEdgeCnt;
		stats.timeCompressing = System.currentTimeMillis() - searchStart;

		for(int i = 0; i < cnt; i++)
			if(!list[i].isEdge() && list[i].isDeltaRepresentation())
				stats.deltasFound++;
	}

	private int findObjectsNeedingDelta(ObjectToPack[] list, int cnt, int type) {
		for(ObjectToPack otp : objectsLists[type]) {
			if(otp.isDoNotDelta())
				continue;
			if(otp.isDeltaRepresentation())
				continue;
			otp.setWeight(0);
			list[cnt++] = otp;
		}
		return cnt;
	}

	private void reselectNonDelta(ObjectToPack otp) throws IOException {
		otp.clearDeltaBase();
		otp.clearReuseAsIs();
		boolean old = reuseDeltas;
		reuseDeltas = false;
		reuseSupport.selectObjectRepresentation(this,
				NullProgressMonitor.INSTANCE,
				Collections.singleton(otp));
		reuseDeltas = old;
	}

	private void searchForDeltas(final ProgressMonitor monitor,
								 final ObjectToPack[] list, final int cnt)
			throws
			LargeObjectException, IOException {
		int threads = config.getThreads();
		if(threads == 0)
			threads = Runtime.getRuntime().availableProcessors();
		if(threads <= 1 || cnt <= config.getDeltaSearchWindowSize())
			singleThreadDeltaSearch(monitor, list, cnt);
		else
			parallelDeltaSearch(monitor, list, cnt, threads);
	}

	private void singleThreadDeltaSearch(ProgressMonitor monitor,
										 ObjectToPack[] list, int cnt) throws IOException {
		long totalWeight = 0;
		for(int i = 0; i < cnt; i++) {
			ObjectToPack o = list[i];
			totalWeight += DeltaTask.getAdjustedWeight(o);
		}

		long bytesPerUnit = 1;
		while(DeltaTask.MAX_METER <= (totalWeight / bytesPerUnit))
			bytesPerUnit <<= 10;
		int cost = (int) (totalWeight / bytesPerUnit);
		if(totalWeight % bytesPerUnit != 0)
			cost++;

		beginPhase(PackingPhase.COMPRESSING, monitor, cost);
		new DeltaWindow(config, new DeltaCache(config), reader,
				monitor, bytesPerUnit,
				list, 0, cnt).search();
		endPhase(monitor);
	}

	@SuppressWarnings("Finally")
	private void parallelDeltaSearch(ProgressMonitor monitor,
									 ObjectToPack[] list, int cnt, int threads) throws IOException {
		DeltaCache dc = new ThreadSafeDeltaCache(config);
		ThreadSafeProgressMonitor pm = new ThreadSafeProgressMonitor(monitor);
		DeltaTask.Block taskBlock = new DeltaTask.Block(threads, config,
				reader, dc, pm,
				list, 0, cnt);
		taskBlock.partitionTasks();
		beginPhase(PackingPhase.COMPRESSING, monitor, taskBlock.cost());
		pm.startWorkers(taskBlock.tasks.size());

		Executor executor = config.getExecutor();
		final List<Throwable> errors =
				Collections.synchronizedList(new ArrayList<>(threads));
		if(executor instanceof ExecutorService) {
			runTasks((ExecutorService) executor, pm, taskBlock, errors);
		} else if(executor == null) {
			ExecutorService pool = Executors.newFixedThreadPool(threads);
			Throwable e1 = null;
			try {
				runTasks(pool, pm, taskBlock, errors);
			} catch(Exception e) {
				e1 = e;
			} finally {
				pool.shutdown();
				for(; ; ) {
					try {
						if(pool.awaitTermination(60, TimeUnit.SECONDS)) {
							break;
						}
					} catch(InterruptedException e) {
						if(e1 != null) {
							e.addSuppressed(e1);
						}
					}
				}
			}
		} else {
			for(DeltaTask task : taskBlock.tasks) {
				executor.execute(() -> {
					try {
						task.call();
					} catch(Throwable failure) {
						errors.add(failure);
					}
				});
			}
			try {
				pm.waitForCompletion();
			} catch(InterruptedException ie) {
				throw new IOException(JGitText.get().packingCancelledDuringObjectsWriting, ie);
			}
		}

		if(!errors.isEmpty()) {
			Throwable err = errors.get(0);
			if(err instanceof Error)
				throw (Error) err;
			if(err instanceof RuntimeException)
				throw (RuntimeException) err;
			if(err instanceof IOException)
				throw (IOException) err;

			throw new IOException(err.getMessage(), err);
		}
		endPhase(monitor);
	}

	private static void runTasks(ExecutorService pool,
								 ThreadSafeProgressMonitor pm,
								 DeltaTask.Block tb, List<Throwable> errors) throws IOException {
		List<Future<?>> futures = new ArrayList<>(tb.tasks.size());
		for(DeltaTask task : tb.tasks)
			futures.add(pool.submit(task));

		try {
			pm.waitForCompletion();
			for(Future<?> f : futures) {
				try {
					f.get();
				} catch(ExecutionException failed) {
					errors.add(failed.getCause());
				}
			}
		} catch(InterruptedException ie) {
			for(Future<?> f : futures)
				f.cancel(true);
			throw new IOException(
					JGitText.get().packingCancelledDuringObjectsWriting, ie);
		}
	}

	private void writeObjects(PackOutputStream out) throws IOException {
		writeObjects(out, objectsLists[OBJ_COMMIT]);
		writeObjects(out, objectsLists[OBJ_TAG]);
		writeObjects(out, objectsLists[OBJ_TREE]);
		writeObjects(out, objectsLists[OBJ_BLOB]);
	}

	private void writeObjects(PackOutputStream out, List<ObjectToPack> list)
			throws IOException {
		if(list.isEmpty())
			return;

		typeStats = stats.objectTypes[list.get(0).getType()];
		long beginOffset = out.length();

		if(reuseSupport != null) {
			reuseSupport.writeObjects(out, list);
		} else {
			for(ObjectToPack otp : list)
				out.writeObject(otp);
		}

		typeStats.bytes += out.length() - beginOffset;
		typeStats.cntObjects = list.size();
	}

	void writeObject(PackOutputStream out, ObjectToPack otp) throws IOException {
		if(!otp.isWritten())
			writeObjectImpl(out, otp);
	}

	private void writeObjectImpl(PackOutputStream out, ObjectToPack otp)
			throws IOException {
		if(otp.wantWrite()) {
			reselectNonDelta(otp);
		}
		otp.markWantWrite();

		while(otp.isReuseAsIs()) {
			writeBase(out, otp.getDeltaBase());
			if(otp.isWritten())
				return;

			crc32.reset();
			otp.setOffset(out.length());
			try {
				reuseSupport.copyObjectAsIs(out, otp, reuseValidate);
				out.endObject();
				otp.setCRC((int) crc32.getValue());
				typeStats.reusedObjects++;
				if(otp.isDeltaRepresentation()) {
					typeStats.reusedDeltas++;
					typeStats.deltaBytes += out.length() - otp.getOffset();
				}
				return;
			} catch(StoredObjectRepresentationNotAvailableException gone) {
				if(otp.getOffset() == out.length()) {
					otp.setOffset(0);
					otp.clearDeltaBase();
					otp.clearReuseAsIs();
					reuseSupport.selectObjectRepresentation(this,
							NullProgressMonitor.INSTANCE,
							Collections.singleton(otp));
					continue;
				}

				CorruptObjectException coe;
				coe = new CorruptObjectException(otp, "");
				coe.initCause(gone);
				throw coe;
			}
		}

		if(otp.isDeltaRepresentation()) {
			writeDeltaObjectDeflate(out, otp);
		} else {
			writeWholeObjectDeflate(out, otp);
		}
		out.endObject();
		otp.setCRC((int) crc32.getValue());
	}

	private void writeBase(PackOutputStream out, ObjectToPack base)
			throws IOException {
		if(base != null && !base.isWritten() && !base.isEdge())
			writeObjectImpl(out, base);
	}

	private void writeWholeObjectDeflate(PackOutputStream out,
										 final ObjectToPack otp) throws IOException {
		final Deflater deflater = deflater();
		final ObjectLoader ldr = reader.open(otp, otp.getType());

		crc32.reset();
		otp.setOffset(out.length());
		out.writeHeader(otp, ldr.getSize());

		deflater.reset();
		DeflaterOutputStream dst = new DeflaterOutputStream(out, deflater);
		ldr.copyTo(dst);
		dst.finish();
	}

	private void writeDeltaObjectDeflate(PackOutputStream out,
										 final ObjectToPack otp) throws IOException {
		writeBase(out, otp.getDeltaBase());

		crc32.reset();
		otp.setOffset(out.length());

		DeltaCache.Ref ref = otp.popCachedDelta();
		if(ref != null) {
			byte[] zbuf = ref.get();
			if(zbuf != null) {
				out.writeHeader(otp, otp.getCachedSize());
				out.write(zbuf);
				typeStats.cntDeltas++;
				typeStats.deltaBytes += out.length() - otp.getOffset();
				return;
			}
		}

		try(TemporaryBuffer.Heap delta = delta(otp)) {
			out.writeHeader(otp, delta.length());

			Deflater deflater = deflater();
			deflater.reset();
			DeflaterOutputStream dst = new DeflaterOutputStream(out, deflater);
			delta.writeTo(dst, null);
			dst.finish();
		}
		typeStats.cntDeltas++;
		typeStats.deltaBytes += out.length() - otp.getOffset();
	}

	private TemporaryBuffer.Heap delta(ObjectToPack otp)
			throws IOException {
		DeltaIndex index = new DeltaIndex(buffer(otp.getDeltaBaseId()));
		byte[] res = buffer(otp);

		TemporaryBuffer.Heap delta = new TemporaryBuffer.Heap(res.length);
		index.encode(delta, res);
		return delta;
	}

	private byte[] buffer(AnyObjectId objId) throws IOException {
		return buffer(config, reader, objId);
	}

	static byte[] buffer(PackConfig config, ObjectReader or, AnyObjectId objId) throws IOException {
		return or.open(objId).getCachedBytes(config.getBigFileThreshold());
	}

	private Deflater deflater() {
		if(myDeflater == null)
			myDeflater = new Deflater(config.getCompressionLevel());
		return myDeflater;
	}

	private void writeChecksum(PackOutputStream out) throws IOException {
		packcsum = out.getDigest();
		out.write(packcsum);
	}

	private void findObjectsToPack(@NonNull ProgressMonitor countingMonitor,
								   @NonNull ObjectWalk walker, @NonNull Set<? extends ObjectId> want,
								   @NonNull Set<? extends ObjectId> have,
								   @NonNull Set<? extends ObjectId> noBitmaps) throws IOException {
		final long countingStart = System.currentTimeMillis();
		beginPhase(PackingPhase.COUNTING, countingMonitor, ProgressMonitor.UNKNOWN);

		stats.interestingObjects = Collections.unmodifiableSet(new HashSet<ObjectId>(want));
		stats.uninterestingObjects = Collections.unmodifiableSet(new HashSet<ObjectId>(have));
		excludeFromBitmapSelection = noBitmaps;

		canBuildBitmaps = config.isBuildBitmaps() && !shallowPack && have.isEmpty()
				&& (excludeInPacks == null || excludeInPacks.length == 0);
		if(!shallowPack && useBitmaps) {
			BitmapIndex bitmapIndex = reader.getBitmapIndex();
			if(bitmapIndex != null) {
				BitmapWalker bitmapWalker = new BitmapWalker(
						walker, bitmapIndex, countingMonitor);
				findObjectsToPackUsingBitmaps(bitmapWalker, want, have);
				endPhase(countingMonitor);
				stats.timeCounting = System.currentTimeMillis() - countingStart;
				stats.bitmapIndexMisses = bitmapWalker.getCountOfBitmapIndexMisses();
				return;
			}
		}

		List<ObjectId> all = new ArrayList<>(want.size() + have.size());
		all.addAll(want);
		all.addAll(have);

		final RevFlag include = walker.newFlag("include");
		final RevFlag added = walker.newFlag("added");

		walker.carry(include);

		int haveEst = have.size();
		if(have.isEmpty()) {
			walker.sort(RevSort.COMMIT_TIME_DESC);
		} else {
			walker.sort(RevSort.TOPO);
			if(thin)
				walker.sort(RevSort.BOUNDARY, true);
		}

		List<RevObject> wantObjs = new ArrayList<>(want.size());
		List<RevObject> haveObjs = new ArrayList<>(haveEst);
		List<RevTag> wantTags = new ArrayList<>(want.size());

		AsyncRevObjectQueue q = walker.parseAny(all, true);
		try {
			for(; ; ) {
				try {
					RevObject o = q.next();
					if(o == null)
						break;
					if(have.contains(o))
						haveObjs.add(o);
					if(want.contains(o)) {
						o.add(include);
						wantObjs.add(o);
						if(o instanceof RevTag)
							wantTags.add((RevTag) o);
					}
				} catch(MissingObjectException e) {
					if(ignoreMissingUninteresting
							&& have.contains(e.getObjectId()))
						continue;
					throw e;
				}
			}
		} finally {
			q.release();
		}

		if(!wantTags.isEmpty()) {
			all = new ArrayList<>(wantTags.size());
			for(RevTag tag : wantTags)
				all.add(tag.getObject());
			q = walker.parseAny(all, true);
			try {
				while(q.next() != null) {
				}
			} finally {
				q.release();
			}
		}

		if(walker instanceof DepthWalk.ObjectWalk) {
			DepthWalk.ObjectWalk depthWalk = (DepthWalk.ObjectWalk) walker;
			for(RevObject obj : wantObjs) {
				depthWalk.markRoot(obj);
			}

			for(RevObject obj : haveObjs) {
				if(obj instanceof RevCommit) {
					RevTree t = ((RevCommit) obj).getTree();
					depthWalk.markUninteresting(t);
				}
			}

			if(unshallowObjects != null) {
				for(ObjectId id : unshallowObjects) {
					depthWalk.markUnshallow(walker.parseAny(id));
				}
			}
		} else {
			for(RevObject obj : wantObjs)
				walker.markStart(obj);
		}
		for(RevObject obj : haveObjs)
			walker.markUninteresting(obj);

		final int maxBases = config.getDeltaSearchWindowSize();
		Set<RevTree> baseTrees = new HashSet<>();
		BlockList<RevCommit> commits = new BlockList<>();
		Set<ObjectId> roots = new HashSet<>();
		RevCommit c;
		while((c = walker.next()) != null) {
			if(exclude(c))
				continue;
			if(c.has(RevFlag.UNINTERESTING)) {
				if(baseTrees.size() <= maxBases)
					baseTrees.add(c.getTree());
				continue;
			}

			commits.add(c);
			if(c.getParentCount() == 0) {
				roots.add(c.copy());
			}
			countingMonitor.update(1);
		}
		stats.rootCommits = Collections.unmodifiableSet(roots);

		if(shallowPack) {
			for(RevCommit cmit : commits) {
				addObject(cmit, 0);
			}
		} else {
			int commitCnt = 0;
			boolean putTagTargets = false;
			for(RevCommit cmit : commits) {
				if(!cmit.has(added)) {
					cmit.add(added);
					addObject(cmit, 0);
					commitCnt++;
				}

				for(int i = 0; i < cmit.getParentCount(); i++) {
					RevCommit p = cmit.getParent(i);
					if(!p.has(added) && !p.has(RevFlag.UNINTERESTING)
							&& !exclude(p)) {
						p.add(added);
						addObject(p, 0);
						commitCnt++;
					}
				}

				if(!putTagTargets && 4096 < commitCnt) {
					for(ObjectId id : tagTargets) {
						RevObject obj = walker.lookupOrNull(id);
						if(obj instanceof RevCommit
								&& obj.has(include)
								&& !obj.has(RevFlag.UNINTERESTING)
								&& !obj.has(added)) {
							obj.add(added);
							addObject(obj, 0);
						}
					}
					putTagTargets = true;
				}
			}
		}

		if(thin && !baseTrees.isEmpty()) {
			BaseSearch bases = new BaseSearch(countingMonitor, baseTrees,
					objectsMap, edgeObjects, reader);
			RevObject o;
			while((o = walker.nextObject()) != null) {
				if(o.has(RevFlag.UNINTERESTING))
					continue;
				if(exclude(o))
					continue;

				int pathHash = walker.getPathHashCode();
				byte[] pathBuf = walker.getPathBuffer();
				int pathLen = walker.getPathLength();
				bases.addBase(o.getType(), pathBuf, pathLen, pathHash);
				if(!depthSkip(o, walker)) {
					filterAndAddObject(o, o.getType(), pathHash, want);
				}
				countingMonitor.update(1);
			}
		} else {
			RevObject o;
			while((o = walker.nextObject()) != null) {
				if(o.has(RevFlag.UNINTERESTING))
					continue;
				if(exclude(o))
					continue;
				if(!depthSkip(o, walker)) {
					filterAndAddObject(o, o.getType(), walker.getPathHashCode(),
							want);
				}
				countingMonitor.update(1);
			}
		}

		for(CachedPack pack : cachedPacks)
			countingMonitor.update((int) pack.getObjectCount());
		endPhase(countingMonitor);
		stats.timeCounting = System.currentTimeMillis() - countingStart;
		stats.bitmapIndexMisses = -1;
	}

	private void findObjectsToPackUsingBitmaps(
			BitmapWalker bitmapWalker, Set<? extends ObjectId> want,
			Set<? extends ObjectId> have)
			throws
			IOException {
		BitmapBuilder haveBitmap = bitmapWalker.findObjects(have, null, true);
		BitmapBuilder wantBitmap = bitmapWalker.findObjects(want, haveBitmap,
				false);
		BitmapBuilder needBitmap = wantBitmap.andNot(haveBitmap);

		if(useCachedPacks && reuseSupport != null && !reuseValidate
				&& (excludeInPacks == null || excludeInPacks.length == 0))
			cachedPacks.addAll(
					reuseSupport.getCachedPacksAndUpdate(needBitmap));

		for(BitmapObject obj : needBitmap) {
			ObjectId objectId = obj.getObjectId();
			if(exclude(objectId)) {
				needBitmap.remove(objectId);
				continue;
			}
			filterAndAddObject(objectId, obj.getType(), 0, want);
		}

		if(thin)
			haveObjects = haveBitmap;
	}

	private static void pruneEdgesFromObjectList(List<ObjectToPack> list) {
		final int size = list.size();
		int src = 0;
		int dst = 0;

		for(; src < size; src++) {
			ObjectToPack obj = list.get(src);
			if(obj.isEdge())
				continue;
			if(dst != src)
				list.set(dst, obj);
			dst++;
		}

		while(dst < list.size())
			list.remove(list.size() - 1);
	}

	public void addObject(RevObject object)
			throws IncorrectObjectTypeException {
		if(!exclude(object))
			addObject(object, 0);
	}

	private void addObject(RevObject object, int pathHashCode) {
		addObject(object, object.getType(), pathHashCode);
	}

	private void addObject(
			final AnyObjectId src, final int type, final int pathHashCode) {
		final ObjectToPack otp;
		if(reuseSupport != null)
			otp = reuseSupport.newObjectToPack(src, type);
		else
			otp = new ObjectToPack(src, type);
		otp.setPathHash(pathHashCode);
		objectsLists[type].add(otp);
		objectsMap.add(otp);
	}

	private boolean depthSkip(@NonNull RevObject obj, ObjectWalk walker) {
		long treeDepth = walker.getTreeDepth();

		if(obj.getType() == OBJ_BLOB) {
			treeDepth++;
		} else {
			stats.treesTraversed++;
		}

		if(filterSpec.getTreeDepthLimit() < 0 ||
				treeDepth <= filterSpec.getTreeDepthLimit()) {
			return false;
		}

		walker.skipTree();
		return true;
	}

	private void filterAndAddObject(@NonNull AnyObjectId src, int type,
									int pathHashCode, @NonNull Set<? extends AnyObjectId> want)
			throws IOException {

		boolean reject =
				(!filterSpec.allowsType(type) && !want.contains(src)) || (filterSpec.getBlobLimit() >= 0 &&
						type == OBJ_BLOB && !want.contains(src) && reader.getObjectSize(src, OBJ_BLOB) > filterSpec.getBlobLimit());
		if(!reject) addObject(src, type, pathHashCode);
	}

	private boolean exclude(AnyObjectId objectId) {
		if(excludeInPacks == null) return false;
		if(excludeInPackLast.contains(objectId)) return true;
		for(ObjectIdSet idx : excludeInPacks) {
			if(idx.contains(objectId)) {
				excludeInPackLast = idx;
				return true;
			}
		}
		return false;
	}

	public void select(ObjectToPack otp, StoredObjectRepresentation next) {
		int nFmt = next.getFormat();

		if(!cachedPacks.isEmpty()) {
			if(otp.isEdge()) return;
			if(nFmt == PACK_WHOLE || nFmt == PACK_DELTA) {
				for(CachedPack pack : cachedPacks) {
					if(pack.hasObject(otp, next)) {
						otp.setEdge();
						otp.clearDeltaBase();
						otp.clearReuseAsIs();
						pruneCurrentObjectList = true;
						return;
					}
				}
			}
		}

		if(nFmt == PACK_DELTA && reuseDeltas && reuseDeltaFor(otp)) {
			ObjectId baseId = next.getDeltaBase();
			ObjectToPack ptr = objectsMap.get(baseId);
			if(ptr != null && !ptr.isEdge()) {
				otp.setDeltaBase(ptr);
				otp.setReuseAsIs();
			} else if(thin && have(ptr, baseId)) {
				otp.setDeltaBase(baseId);
				otp.setReuseAsIs();
			} else {
				otp.clearDeltaBase();
				otp.clearReuseAsIs();
			}
		} else if(nFmt == PACK_WHOLE && config.isReuseObjects()) {
			int nWeight = next.getWeight();
			if(otp.isReuseAsIs() && !otp.isDeltaRepresentation()) {
				if(otp.getWeight() <= nWeight) return;
			}
			otp.clearDeltaBase();
			otp.setReuseAsIs();
			otp.setWeight(nWeight);
		} else {
			otp.clearDeltaBase();
			otp.clearReuseAsIs();
		}

		otp.setDeltaAttempted(reuseDeltas && next.wasDeltaAttempted());
		otp.select(next);
	}

	private boolean have(ObjectToPack ptr, AnyObjectId objectId) {
		return (ptr != null && ptr.isEdge()) || (haveObjects != null && haveObjects.contains(objectId));
	}

	public boolean prepareBitmapIndex(ProgressMonitor pm) throws IOException {
		if(!canBuildBitmaps || getObjectCount() > Integer.MAX_VALUE || !cachedPacks.isEmpty())
			return false;
		if(pm == null) pm = NullProgressMonitor.INSTANCE;

		int numCommits = objectsLists[OBJ_COMMIT].size();
		List<ObjectToPack> byName = sortByName();
		sortedByName = null;
		objectsLists = null;
		objectsMap = null;
		writeBitmaps = new PackBitmapIndexBuilder(byName);

		PackWriterBitmapPreparer bitmapPreparer = new PackWriterBitmapPreparer(reader, writeBitmaps, pm, stats.interestingObjects, config);
		Collection<BitmapCommit> selectedCommits = bitmapPreparer.selectCommits(numCommits, excludeFromBitmapSelection);
		beginPhase(PackingPhase.BUILDING_BITMAPS, pm, selectedCommits.size());

		BitmapWalker walker = bitmapPreparer.newBitmapWalker();
		AnyObjectId last = null;
		for(BitmapCommit cmit : selectedCommits) {
			if(!cmit.isReuseWalker()) walker = bitmapPreparer.newBitmapWalker();
			BitmapBuilder bitmap = walker.findObjects(Collections.singleton(cmit), null, false);

			if(last != null && cmit.isReuseWalker() && !bitmap.contains(last))
				throw new IllegalStateException(MessageFormat.format(JGitText.get().bitmapMissingObject, cmit.name(), last.name()));

			last = BitmapCommit.copyFrom(cmit).build();
			writeBitmaps.processBitmapForWrite(cmit, bitmap.build(), cmit.getFlags());

			walker.setPrevCommit(last);
			walker.setPrevBitmap(bitmap);

			pm.update(1);
		}

		endPhase(pm);
		return true;
	}

	private boolean reuseDeltaFor(ObjectToPack otp) {
		int type = otp.getType();
		if((type & 2) != 0) return true;
		if(type == OBJ_COMMIT) return reuseDeltaCommits;
		return type != OBJ_TAG;
	}

	private class MutableState {
		private static final long OBJECT_TO_PACK_SIZE = (2 * 8) + (2 * 8) + (2 * 8) + (8 + 8) + 8 + 40 + 8;
		private final long totalDeltaSearchBytes;
		private volatile PackingPhase phase;

		MutableState() {
			phase = PackingPhase.COUNTING;
			if(config.isDeltaCompress()) {
				int threads = config.getThreads();
				if(threads <= 0) threads = Runtime.getRuntime().availableProcessors();
				totalDeltaSearchBytes = (threads * config.getDeltaSearchMemoryLimit()) + config.getBigFileThreshold();
			} else
				totalDeltaSearchBytes = 0;
		}

		State snapshot() {
			long objCnt = 0;
			BlockList<ObjectToPack>[] lists = objectsLists;
			if(lists != null) {
				objCnt += lists[OBJ_COMMIT].size();
				objCnt += lists[OBJ_TREE].size();
				objCnt += lists[OBJ_BLOB].size();
				objCnt += lists[OBJ_TAG].size();
			}

			long bytesUsed = OBJECT_TO_PACK_SIZE * objCnt;
			PackingPhase curr = phase;
			if(curr == PackingPhase.COMPRESSING) bytesUsed += totalDeltaSearchBytes;
			return new State(curr, bytesUsed);
		}
	}

	public enum PackingPhase {

		COUNTING,
		GETTING_SIZES,
		FINDING_SOURCES,
		COMPRESSING,
		WRITING,
		BUILDING_BITMAPS
	}

	public class State {
		private final PackingPhase phase;
		private final long bytesUsed;

		State(PackingPhase phase, long bytesUsed) {
			this.phase = phase;
			this.bytesUsed = bytesUsed;
		}

		public PackConfig getConfig() {
			return config;
		}

		@Override
		public String toString() {
			return "PackWriter.State[" + phase + ", memory=" + bytesUsed + "]";
		}
	}

	public static class PackfileUriConfig {
		@NonNull
		private final PacketLineOut pckOut;

		@NonNull
		private final Collection<String> protocolsSupported;

		@NonNull
		private final CachedPackUriProvider cachedPackUriProvider;

		public PackfileUriConfig(@NonNull PacketLineOut pckOut,
								 @NonNull Collection<String> protocolsSupported,
								 @NonNull CachedPackUriProvider cachedPackUriProvider) {
			this.pckOut = pckOut;
			this.protocolsSupported = protocolsSupported;
			this.cachedPackUriProvider = cachedPackUriProvider;
		}
	}
}
