/*
 * Copyright (C) 2008-2011, Google Inc.
 * Copyright (C) 2007-2008, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.TooLargeObjectInPackException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.internal.storage.pack.BinaryDelta;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.BatchingProgressMonitor;
import org.eclipse.jgit.lib.BlobObjectChecker;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.InflaterCache;
import org.eclipse.jgit.lib.MutableObjectId;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectChecker;
import org.eclipse.jgit.lib.ObjectDatabase;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectIdOwnerMap;
import org.eclipse.jgit.lib.ObjectIdSubclassMap;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.ObjectStream;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.util.BlockList;
import org.eclipse.jgit.util.IO;
import org.eclipse.jgit.util.LongMap;
import org.eclipse.jgit.util.NB;
import org.eclipse.jgit.util.sha1.SHA1;

public abstract class PackParser {
	private static final int BUFFER_SIZE = 8192;

	public enum Source {
		INPUT,
		DATABASE
	}

	private InflaterStream inflater;
	private final byte[] tempBuffer;
	private final byte[] hdrBuf;
	private final SHA1 objectHasher = SHA1.newInstance();
	private final MutableObjectId tempObjectId;
	private InputStream in;
	byte[] buf;
	private long bBase;
	private int bOffset;
	int bAvail;
	private ObjectChecker objCheck;
	private boolean allowThin;
	private boolean checkObjectCollisions;
	private boolean needBaseObjectIds;
	private boolean checkEofAfterPackFooter;
	private boolean expectDataAfterPackFooter;
	private long expectedObjectCount;
	private PackedObjectInfo[] entries;
	private ObjectIdSubclassMap<ObjectId> newObjectIds;
	private int deltaCount;
	private int entryCount;
	private ObjectIdOwnerMap<DeltaChain> baseById;
	private ObjectIdSubclassMap<ObjectId> baseObjectIds;
	private LongMap<UnresolvedDelta> baseByPos;
	private BlockList<PackedObjectInfo> collisionCheckObjs;
	private MessageDigest packDigest;
	private ObjectReader readCurs;
	private String lockMessage;
	private long maxObjectSizeLimit;

	private final ReceivedPackStatistics.Builder stats =
			new ReceivedPackStatistics.Builder();

	protected PackParser(ObjectDatabase odb, InputStream src) {
		ObjectDatabase objectDatabase = odb.newCachedDatabase();
		in = src;

		inflater = new InflaterStream();
		readCurs = objectDatabase.newReader();
		buf = new byte[BUFFER_SIZE];
		tempBuffer = new byte[BUFFER_SIZE];
		hdrBuf = new byte[64];
		tempObjectId = new MutableObjectId();
		packDigest = Constants.newMessageDigest();
		checkObjectCollisions = true;
	}

	public boolean isAllowThin() {
		return allowThin;
	}

	public void setAllowThin(boolean allow) {
		allowThin = allow;
	}

	protected boolean isCheckObjectCollisions() {
		return checkObjectCollisions;
	}

	protected void setCheckObjectCollisions() {
		checkObjectCollisions = false;
	}

	public void setNeedNewObjectIds(boolean b) {
		if(b)
			newObjectIds = new ObjectIdSubclassMap<>();
		else
			newObjectIds = null;
	}

	private boolean needNewObjectIds() {
		return newObjectIds != null;
	}

	public void setNeedBaseObjectIds(boolean b) {
		this.needBaseObjectIds = b;
	}

	public boolean isCheckEofAfterPackFooter() {
		return checkEofAfterPackFooter;
	}

	public void setCheckEofAfterPackFooter(boolean b) {
		checkEofAfterPackFooter = b;
	}

	public void setExpectDataAfterPackFooter(boolean e) {
		expectDataAfterPackFooter = e;
	}

	public ObjectIdSubclassMap<ObjectId> getNewObjectIds() {
		if(newObjectIds != null)
			return newObjectIds;
		return new ObjectIdSubclassMap<>();
	}

	public ObjectIdSubclassMap<ObjectId> getBaseObjectIds() {
		if(baseObjectIds != null)
			return baseObjectIds;
		return new ObjectIdSubclassMap<>();
	}

	public void setObjectChecker(ObjectChecker oc) {
		objCheck = oc;
	}

	public String getLockMessage() {
		return lockMessage;
	}

	public void setLockMessage(String msg) {
		lockMessage = msg;
	}

	public void setMaxObjectSizeLimit(long limit) {
		maxObjectSizeLimit = limit;
	}

	public int getObjectCount() {
		return entryCount;
	}

	public PackedObjectInfo getObject(int nth) {
		return entries[nth];
	}

	public List<PackedObjectInfo> getSortedObjectList(
			Comparator<PackedObjectInfo> cmp) {
		Arrays.sort(entries, 0, entryCount, cmp);
		List<PackedObjectInfo> list = Arrays.asList(entries);
		if(entryCount < entries.length)
			list = list.subList(0, entryCount);
		return list;
	}

	public long getPackSize() {
		return -1;
	}

	public ReceivedPackStatistics getReceivedPackStatistics() {
		return stats.build();
	}

	public final PackLock parse(ProgressMonitor progress) throws IOException {
		return parse(progress, progress);
	}

	public PackLock parse(ProgressMonitor receiving, ProgressMonitor resolving)
			throws IOException {
		if(receiving == null)
			receiving = NullProgressMonitor.INSTANCE;
		if(resolving == null)
			resolving = NullProgressMonitor.INSTANCE;

		if(receiving == resolving)
			receiving.start(2);
		try {
			readPackHeader();

			entries = new PackedObjectInfo[(int) expectedObjectCount];
			baseById = new ObjectIdOwnerMap<>();
			baseByPos = new LongMap<>();
			collisionCheckObjs = new BlockList<>();

			receiving.beginTask(JGitText.get().receivingObjects,
					(int) expectedObjectCount);
			try {
				for(int done = 0; done < expectedObjectCount; done++) {
					indexOneObject();
					receiving.update(1);
					if(receiving.isCancelled())
						throw new IOException(JGitText.get().downloadCancelled);
				}
				readPackFooter();
				endInput();
			} finally {
				receiving.endTask();
			}

			if(!collisionCheckObjs.isEmpty()) {
				checkObjectCollision();
			}

			if(deltaCount > 0) {
				processDeltas(resolving);
			}

			packDigest = null;
			baseById = null;
			baseByPos = null;
		} finally {
			try {
				if(readCurs != null)
					readCurs.close();
			} finally {
				readCurs = null;
			}

			try {
				inflater.release();
			} finally {
				inflater = null;
			}
		}
		return null;
	}

	private void processDeltas(ProgressMonitor resolving) throws IOException {
		if(resolving instanceof BatchingProgressMonitor) {
			((BatchingProgressMonitor) resolving).setDelayStart(1000,
					TimeUnit.MILLISECONDS);
		}
		resolving.beginTask(JGitText.get().resolvingDeltas, deltaCount);
		resolveDeltas(resolving);
		if(entryCount < expectedObjectCount) {
			if(!isAllowThin()) {
				throw new IOException(MessageFormat.format(
						JGitText.get().packHasUnresolvedDeltas,
						expectedObjectCount - entryCount));
			}

			resolveDeltasWithExternalBases(resolving);

			if(entryCount < expectedObjectCount) {
				throw new IOException(MessageFormat.format(
						JGitText.get().packHasUnresolvedDeltas,
						expectedObjectCount - entryCount));
			}
		}
		resolving.endTask();
	}

	private void resolveDeltas(ProgressMonitor progress)
			throws IOException {
		final int last = entryCount;
		for(int i = 0; i < last; i++) {
			resolveDeltas(entries[i], progress);
			if(progress.isCancelled())
				throw new IOException(
						JGitText.get().downloadCancelledDuringIndexing);
		}
	}

	private void resolveDeltas(final PackedObjectInfo oe,
							   ProgressMonitor progress) throws IOException {
		UnresolvedDelta children = firstChildOf(oe);
		if(children == null)
			return;

		DeltaVisit visit = new DeltaVisit();
		visit.nextChild = children;

		ObjectTypeAndSize info = openDatabase(oe, new ObjectTypeAndSize());
		switch(info.type) {
			case Constants.OBJ_COMMIT:
			case Constants.OBJ_TREE:
			case Constants.OBJ_BLOB:
			case Constants.OBJ_TAG:
				visit.data = inflateAndReturn(Source.DATABASE, info.size);
				visit.id = oe;
				break;
			default:
				throw new IOException(MessageFormat.format(
						JGitText.get().unknownObjectType,
						info.type));
		}

		if(!checkCRC(oe.getCRC())) {
			throw new IOException(MessageFormat.format(
					JGitText.get().corruptionDetectedReReadingAt,
					oe.getOffset()));
		}

		resolveDeltas(Objects.requireNonNull(visit.next()), info.type, info, progress);
	}

	private void resolveDeltas(DeltaVisit visit, final int type,
							   ObjectTypeAndSize info, ProgressMonitor progress)
			throws IOException {
		stats.addDeltaObject(type);
		do {
			progress.update(1);
			info = openDatabase(visit.delta, info);
			switch(info.type) {
				case Constants.OBJ_OFS_DELTA:
				case Constants.OBJ_REF_DELTA:
					break;

				default:
					throw new IOException(MessageFormat.format(
							JGitText.get().unknownObjectType,
							info.type));
			}

			byte[] delta = inflateAndReturn(Source.DATABASE, info.size);
			checkIfTooLarge(type, BinaryDelta.getResultSize(delta));

			visit.data = BinaryDelta.apply(visit.parent.data, delta);

			if(!checkCRC(visit.delta.crc))
				throw new IOException(MessageFormat.format(
						JGitText.get().corruptionDetectedReReadingAt,
						visit.delta.position));

			SHA1 objectDigest = objectHasher.reset();
			objectDigest.update(Constants.encodedTypeString(type));
			objectDigest.update((byte) ' ');
			objectDigest.update(Constants.encodeASCII(visit.data.length));
			objectDigest.update((byte) 0);
			objectDigest.update(visit.data);
			objectDigest.digest(tempObjectId);

			verifySafeObject(tempObjectId, type, visit.data);
			if(isCheckObjectCollisions() && readCurs.has(tempObjectId)) {
				checkObjectCollision(tempObjectId, type, visit.data
				);
			}

			PackedObjectInfo oe;
			oe = newInfo(tempObjectId, visit.delta);
			oe.setOffset(visit.delta.position);
			oe.setType(type);
			onInflatedObjectData(oe, type, visit.data);
			addObjectAndTrack(oe);
			visit.id = oe;

			visit.nextChild = firstChildOf(oe);
			visit = visit.next();
		} while(visit != null);
	}

	private void checkIfTooLarge(int typeCode, long size)
			throws IOException {
		if(0 < maxObjectSizeLimit && maxObjectSizeLimit < size) {
			switch(typeCode) {
				case Constants.OBJ_COMMIT:
				case Constants.OBJ_TREE:
				case Constants.OBJ_BLOB:
				case Constants.OBJ_TAG:

				case Constants.OBJ_OFS_DELTA:
				case Constants.OBJ_REF_DELTA:
					throw new TooLargeObjectInPackException(size, maxObjectSizeLimit);

				default:
					throw new IOException(MessageFormat.format(
							JGitText.get().unknownObjectType,
							typeCode));
			}
		}
		if(size > Integer.MAX_VALUE - 8) {
			throw new TooLargeObjectInPackException(size, Integer.MAX_VALUE - 8);
		}
	}

	protected ObjectTypeAndSize readObjectHeader(ObjectTypeAndSize info)
			throws IOException {
		int hdrPtr = 0;
		int c = readFrom(Source.DATABASE);
		hdrBuf[hdrPtr++] = (byte) c;

		info.type = (c >> 4) & 7;
		long sz = c & 15;
		int shift = 4;
		while((c & 0x80) != 0) {
			c = readFrom(Source.DATABASE);
			hdrBuf[hdrPtr++] = (byte) c;
			sz += ((long) (c & 0x7f)) << shift;
			shift += 7;
		}
		info.size = sz;

		switch(info.type) {
			case Constants.OBJ_COMMIT:
			case Constants.OBJ_TREE:
			case Constants.OBJ_BLOB:
			case Constants.OBJ_TAG:
				onObjectHeader(Source.DATABASE, hdrBuf, hdrPtr);
				break;

			case Constants.OBJ_OFS_DELTA:
				c = readFrom(Source.DATABASE);
				hdrBuf[hdrPtr++] = (byte) c;
				while((c & 128) != 0) {
					c = readFrom(Source.DATABASE);
					hdrBuf[hdrPtr++] = (byte) c;
				}
				onObjectHeader(Source.DATABASE, hdrBuf, hdrPtr);
				break;

			case Constants.OBJ_REF_DELTA:
				System.arraycopy(buf, fill(Source.DATABASE, 20), hdrBuf, hdrPtr, 20);
				hdrPtr += 20;
				use(20);
				onObjectHeader(Source.DATABASE, hdrBuf, hdrPtr);
				break;

			default:
				throw new IOException(MessageFormat.format(
						JGitText.get().unknownObjectType,
						info.type));
		}
		return info;
	}

	private UnresolvedDelta removeBaseById(AnyObjectId id) {
		final DeltaChain d = baseById.get(id);
		return d != null ? d.remove() : null;
	}

	private static UnresolvedDelta reverse(UnresolvedDelta c) {
		UnresolvedDelta tail = null;
		while(c != null) {
			final UnresolvedDelta n = c.next;
			c.next = tail;
			tail = c;
			c = n;
		}
		return tail;
	}

	private UnresolvedDelta firstChildOf(PackedObjectInfo oe) {
		UnresolvedDelta a = reverse(removeBaseById(oe));
		UnresolvedDelta b = reverse(baseByPos.remove(oe.getOffset()));

		if(a == null)
			return b;
		if(b == null)
			return a;

		UnresolvedDelta first = null;
		UnresolvedDelta last = null;
		while(a != null || b != null) {
			UnresolvedDelta curr;
			if(b == null || (a != null && a.position < b.position)) {
				curr = a;
				a = a.next;
			} else {
				curr = b;
				b = b.next;
			}
			if(last != null)
				last.next = curr;
			else
				first = curr;
			last = curr;
			curr.next = null;
		}
		return first;
	}

	private void resolveDeltasWithExternalBases(ProgressMonitor progress)
			throws IOException {
		growEntries(baseById.size());

		if(needBaseObjectIds)
			baseObjectIds = new ObjectIdSubclassMap<>();

		final List<DeltaChain> missing = new ArrayList<>(64);
		for(DeltaChain baseId : baseById) {
			if(baseId.head == null)
				continue;

			if(needBaseObjectIds)
				baseObjectIds.add(baseId);

			final ObjectLoader ldr;
			try {
				ldr = readCurs.open(baseId);
			} catch(MissingObjectException notFound) {
				missing.add(baseId);
				continue;
			}

			final DeltaVisit visit = new DeltaVisit();
			visit.data = ldr.getCachedBytes(Integer.MAX_VALUE);
			visit.id = baseId;
			final int typeCode = ldr.getType();
			final PackedObjectInfo oe = newInfo(baseId, null);
			oe.setType(typeCode);
			if(onAppendBase(typeCode, visit.data, oe))
				entries[entryCount++] = oe;
			visit.nextChild = firstChildOf(oe);
			resolveDeltas(Objects.requireNonNull(visit.next()), typeCode,
					new ObjectTypeAndSize(), progress);

			if(progress.isCancelled())
				throw new IOException(
						JGitText.get().downloadCancelledDuringIndexing);
		}

		for(DeltaChain base : missing) {
			if(base.head != null)
				throw new MissingObjectException(base, "delta base");
		}

		onEndThinPack();
	}

	private void growEntries(int extraObjects) {
		final PackedObjectInfo[] ne;

		ne = new PackedObjectInfo[(int) expectedObjectCount + extraObjects];
		System.arraycopy(entries, 0, ne, 0, entryCount);
		entries = ne;
	}

	private void readPackHeader() throws IOException {
		if(expectDataAfterPackFooter) {
			if(!in.markSupported())
				throw new IOException(
						JGitText.get().inputStreamMustSupportMark);
			in.mark(buf.length);
		}

		final int hdrln = Constants.PACK_SIGNATURE.length + 4 + 4;
		final int p = fill(Source.INPUT, hdrln);
		for(int k = 0; k < Constants.PACK_SIGNATURE.length; k++)
			if(buf[p + k] != Constants.PACK_SIGNATURE[k])
				throw new IOException(JGitText.get().notAPACKFile);

		final long vers = NB.decodeUInt32(buf, p + 4);
		if(vers != 2 && vers != 3)
			throw new IOException(MessageFormat.format(
					JGitText.get().unsupportedPackVersion, vers));
		final long objectCount = NB.decodeUInt32(buf, p + 8);
		use(hdrln);
		setExpectedObjectCount(objectCount);
		onPackHeader(objectCount);
	}

	private void readPackFooter() throws IOException {
		sync();
		final byte[] actHash = packDigest.digest();

		final int c = fill(Source.INPUT, 20);
		final byte[] srcHash = new byte[20];
		System.arraycopy(buf, c, srcHash, 0, 20);
		use(20);

		if(bAvail != 0 && !expectDataAfterPackFooter)
			throw new CorruptObjectException(MessageFormat.format(
					JGitText.get().expectedEOFReceived,
					"\\x" + Integer.toHexString(buf[bOffset] & 0xff)));
		if(isCheckEofAfterPackFooter()) {
			int eof = in.read();
			if(0 <= eof)
				throw new CorruptObjectException(MessageFormat.format(
						JGitText.get().expectedEOFReceived,
						"\\x" + Integer.toHexString(eof)));
		} else if(bAvail > 0 && expectDataAfterPackFooter) {
			in.reset();
			IO.skipFully(in, bOffset);
		}

		if(!Arrays.equals(actHash, srcHash))
			throw new CorruptObjectException(
					JGitText.get().corruptObjectPackfileChecksumIncorrect);

		onPackFooter(srcHash);
	}

	private void endInput() {
		stats.setNumBytesRead();
		in = null;
	}

	private void indexOneObject() throws IOException {
		final long streamPosition = streamPosition();

		int hdrPtr = 0;
		int c = readFrom(Source.INPUT);
		hdrBuf[hdrPtr++] = (byte) c;

		final int typeCode = (c >> 4) & 7;
		long sz = c & 15;
		int shift = 4;
		while((c & 0x80) != 0) {
			c = readFrom(Source.INPUT);
			hdrBuf[hdrPtr++] = (byte) c;
			sz += ((long) (c & 0x7f)) << shift;
			shift += 7;
		}

		checkIfTooLarge(typeCode, sz);

		switch(typeCode) {
			case Constants.OBJ_COMMIT:
			case Constants.OBJ_TREE:
			case Constants.OBJ_BLOB:
			case Constants.OBJ_TAG:
				stats.addWholeObject(typeCode);
				onBeginWholeObject(streamPosition, typeCode, sz);
				onObjectHeader(Source.INPUT, hdrBuf, hdrPtr);
				whole(streamPosition, typeCode, sz);
				break;

			case Constants.OBJ_OFS_DELTA: {
				stats.addOffsetDelta();
				c = readFrom(Source.INPUT);
				hdrBuf[hdrPtr++] = (byte) c;
				long ofs = c & 127;
				while((c & 128) != 0) {
					ofs += 1;
					c = readFrom(Source.INPUT);
					hdrBuf[hdrPtr++] = (byte) c;
					ofs <<= 7;
					ofs += (c & 127);
				}
				final long base = streamPosition - ofs;
				onBeginOfsDelta(streamPosition, base, sz);
				onObjectHeader(Source.INPUT, hdrBuf, hdrPtr);
				inflateAndSkip(sz);
				UnresolvedDelta n = onEndDelta();
				n.position = streamPosition;
				n.next = baseByPos.put(base, n);
				n.sizeBeforeInflating = streamPosition() - streamPosition;
				deltaCount++;
				break;
			}

			case Constants.OBJ_REF_DELTA: {
				stats.addRefDelta();
				c = fill(Source.INPUT, 20);
				final ObjectId base = ObjectId.fromRaw(buf, c);
				System.arraycopy(buf, c, hdrBuf, hdrPtr, 20);
				hdrPtr += 20;
				use(20);
				DeltaChain r = baseById.get(base);
				if(r == null) {
					r = new DeltaChain(base);
					baseById.add(r);
				}
				onBeginRefDelta(streamPosition, base, sz);
				onObjectHeader(Source.INPUT, hdrBuf, hdrPtr);
				inflateAndSkip(sz);
				UnresolvedDelta n = onEndDelta();
				n.position = streamPosition;
				n.sizeBeforeInflating = streamPosition() - streamPosition;
				r.add(n);
				deltaCount++;
				break;
			}

			default:
				throw new IOException(
						MessageFormat.format(JGitText.get().unknownObjectType,
								typeCode));
		}
	}

	private void whole(long pos, int type, long sz)
			throws IOException {
		SHA1 objectDigest = objectHasher.reset();
		objectDigest.update(Constants.encodedTypeString(type));
		objectDigest.update((byte) ' ');
		objectDigest.update(Constants.encodeASCII(sz));
		objectDigest.update((byte) 0);

		final byte[] data;
		if(type == Constants.OBJ_BLOB) {
			byte[] readBuffer = buffer();
			BlobObjectChecker checker = null;
			if(objCheck != null) {
				checker = objCheck.newBlobObjectChecker();
			}
			if(checker == null) {
				checker = BlobObjectChecker.NULL_CHECKER;
			}
			long cnt = 0;
			try(InputStream inf = inflate(Source.INPUT, sz)) {
				while(cnt < sz) {
					int r = inf.read(readBuffer);
					if(r <= 0)
						break;
					objectDigest.update(readBuffer, 0, r);
					checker.update(readBuffer, 0, r);
					cnt += r;
				}
			}
			objectDigest.digest(tempObjectId);
			checker.endBlob(tempObjectId);
			data = null;
		} else {
			data = inflateAndReturn(Source.INPUT, sz);
			objectDigest.update(data);
			objectDigest.digest(tempObjectId);
			verifySafeObject(tempObjectId, type, data);
		}

		PackedObjectInfo obj = newInfo(tempObjectId, null);
		obj.setOffset(pos);
		obj.setType(type);
		onEndWholeObject(obj);
		if(data != null)
			onInflatedObjectData(obj, type, data);
		addObjectAndTrack(obj);

		if(isCheckObjectCollisions()) {
			collisionCheckObjs.add(obj);
		}
	}

	protected void verifySafeObject(final AnyObjectId id, final int type,
									final byte[] data) throws CorruptObjectException {
		if(objCheck != null) {
			try {
				objCheck.check(id, type, data);
			} catch(CorruptObjectException e) {
				if(e.getErrorType() != null) {
					throw e;
				}
				throw new CorruptObjectException(
						MessageFormat.format(JGitText.get().invalidObject,
								Constants.typeString(type), id.name(),
								e.getMessage()),
						e);
			}
		}
	}

	private void checkObjectCollision() throws IOException {
		for(PackedObjectInfo obj : collisionCheckObjs) {
			if(!readCurs.has(obj)) {
				continue;
			}
			checkObjectCollision(obj);
		}
	}

	private void checkObjectCollision(PackedObjectInfo obj)
			throws IOException {
		ObjectTypeAndSize info = openDatabase(obj, new ObjectTypeAndSize());
		final byte[] readBuffer = buffer();
		final byte[] curBuffer = new byte[readBuffer.length];
		long sz = info.size;
		try(ObjectStream cur = readCurs.open(obj, info.type).openStream()) {
			if(cur.getSize() != sz) {
				throw new IOException(MessageFormat.format(
						JGitText.get().collisionOn, obj.name()));
			}
			try(InputStream pck = inflate(Source.DATABASE, sz)) {
				while(0 < sz) {
					int n = (int) Math.min(readBuffer.length, sz);
					IO.readFully(cur, curBuffer, 0, n);
					IO.readFully(pck, readBuffer, 0, n);
					for(int i = 0; i < n; i++) {
						if(curBuffer[i] != readBuffer[i]) {
							throw new IOException(MessageFormat.format(
									JGitText.get().collisionOn, obj.name()));
						}
					}
					sz -= n;
				}
			}
			stats.incrementObjectsDuplicated();
			stats.incrementNumBytesDuplicated();
		} catch(MissingObjectException ignored) {
		}
	}

	private void checkObjectCollision(AnyObjectId obj, int type, byte[] data) throws IOException {
		try {
			final ObjectLoader ldr = readCurs.open(obj, type);
			final byte[] existingData = ldr.getCachedBytes(data.length);
			if(!Arrays.equals(data, existingData)) {
				throw new IOException(MessageFormat
						.format(JGitText.get().collisionOn, obj.name()));
			}
			stats.incrementObjectsDuplicated();
			stats.incrementNumBytesDuplicated();
		} catch(MissingObjectException ignored) {
		}
	}

	private long streamPosition() {
		return bBase + bOffset;
	}

	private ObjectTypeAndSize openDatabase(PackedObjectInfo obj,
										   ObjectTypeAndSize info) throws IOException {
		bOffset = 0;
		bAvail = 0;
		return seekDatabase(obj, info);
	}

	private ObjectTypeAndSize openDatabase(UnresolvedDelta delta,
										   ObjectTypeAndSize info) throws IOException {
		bOffset = 0;
		bAvail = 0;
		return seekDatabase(delta, info);
	}

	private int readFrom(Source src) throws IOException {
		if(bAvail == 0)
			fill(src, 1);
		bAvail--;
		return buf[bOffset++] & 0xff;
	}

	void use(int cnt) {
		bOffset += cnt;
		bAvail -= cnt;
	}

	int fill(Source src, int need) throws IOException {
		while(bAvail < need) {
			int next = bOffset + bAvail;
			int free = buf.length - next;
			if(free + bAvail < need) {
				switch(src) {
					case INPUT:
						sync();
						break;
					case DATABASE:
						if(bAvail > 0)
							System.arraycopy(buf, bOffset, buf, 0, bAvail);
						bOffset = 0;
						break;
				}
				next = bAvail;
				free = buf.length - next;
			}
			switch(src) {
				case INPUT:
					next = in.read(buf, next, free);
					break;
				case DATABASE:
					next = readDatabase(buf, next, free);
					break;
			}
			if(next <= 0)
				throw new EOFException(
						JGitText.get().packfileIsTruncatedNoParam);
			bAvail += next;
		}
		return bOffset;
	}

	private void sync() throws IOException {
		packDigest.update(buf, 0, bOffset);
		onStoreStream(buf, bOffset);
		if(expectDataAfterPackFooter) {
			if(bAvail > 0) {
				in.reset();
				IO.skipFully(in, bOffset);
				bAvail = 0;
			}
			in.mark(buf.length);
		} else if(bAvail > 0)
			System.arraycopy(buf, bOffset, buf, 0, bAvail);
		bBase += bOffset;
		bOffset = 0;
	}

	protected byte[] buffer() {
		return tempBuffer;
	}

	protected PackedObjectInfo newInfo(AnyObjectId id, UnresolvedDelta delta) {
		PackedObjectInfo oe = new PackedObjectInfo(id);
		if(delta != null)
			oe.setCRC(delta.crc);
		return oe;
	}

	protected void setExpectedObjectCount(long expectedObjectCount) {
		this.expectedObjectCount = expectedObjectCount;
	}

	protected abstract void onStoreStream(byte[] raw, int len)
			throws IOException;

	protected abstract void onObjectHeader(Source src, byte[] raw,
										   int len) throws IOException;

	protected abstract void onObjectData(Source src, byte[] raw, int pos,
										 int len) throws IOException;

	protected abstract void onInflatedObjectData(PackedObjectInfo obj,
												 int typeCode, byte[] data) throws IOException;

	protected abstract void onPackHeader(long objCnt) throws IOException;

	protected abstract void onPackFooter(byte[] hash) throws IOException;

	protected abstract boolean onAppendBase(int typeCode, byte[] data,
											PackedObjectInfo info) throws IOException;

	protected abstract void onEndThinPack() throws IOException;

	protected abstract ObjectTypeAndSize seekDatabase(PackedObjectInfo obj,
													  ObjectTypeAndSize info) throws IOException;

	protected abstract ObjectTypeAndSize seekDatabase(UnresolvedDelta delta,
													  ObjectTypeAndSize info) throws IOException;

	protected abstract int readDatabase(byte[] dst, int pos, int cnt)
			throws IOException;

	protected abstract boolean checkCRC(int oldCRC);

	protected abstract void onBeginWholeObject(long streamPosition, int type,
											   long inflatedSize) throws IOException;

	protected abstract void onEndWholeObject(PackedObjectInfo info)
			throws IOException;

	protected abstract void onBeginOfsDelta(long deltaStreamPosition,
											long baseStreamPosition, long inflatedSize) throws IOException;

	protected abstract void onBeginRefDelta(long deltaStreamPosition,
											AnyObjectId baseId, long inflatedSize) throws IOException;

	protected UnresolvedDelta onEndDelta() {
		return new UnresolvedDelta();
	}

	public static class ObjectTypeAndSize {
		public int type;
		public long size;
	}

	private void inflateAndSkip(long inflatedSize)
			throws IOException {
		try(InputStream inf = inflate(Source.INPUT, inflatedSize)) {
			IO.skipFully(inf, inflatedSize);
		}
	}

	private byte[] inflateAndReturn(Source src, long inflatedSize)
			throws IOException {
		final byte[] dst = new byte[(int) inflatedSize];
		try(InputStream inf = inflate(src, inflatedSize)) {
			IO.readFully(inf, dst, 0, dst.length);
		}
		return dst;
	}

	private InputStream inflate(Source src, long inflatedSize)
			throws IOException {
		inflater.open(src, inflatedSize);
		return inflater;
	}

	private static class DeltaChain extends ObjectIdOwnerMap.Entry {
		UnresolvedDelta head;

		DeltaChain(AnyObjectId id) {
			super(id);
		}

		UnresolvedDelta remove() {
			final UnresolvedDelta r = head;
			if(r != null)
				head = null;
			return r;
		}

		void add(UnresolvedDelta d) {
			d.next = head;
			head = d;
		}
	}

	public static class UnresolvedDelta {
		long position;
		int crc;
		UnresolvedDelta next;
		long sizeBeforeInflating;

		public long getOffset() {
			return position;
		}

		public void setCRC(int crc32) {
			crc = crc32;
		}
	}

	private static class DeltaVisit {
		final UnresolvedDelta delta;
		ObjectId id;
		byte[] data;
		DeltaVisit parent;
		UnresolvedDelta nextChild;

		DeltaVisit() {
			this.delta = null;
		}

		DeltaVisit(DeltaVisit parent) {
			this.parent = parent;
			this.delta = parent.nextChild;
			parent.nextChild = delta.next;
		}

		DeltaVisit next() {
			if(parent != null && parent.nextChild == null) {
				parent.data = null;
				parent = parent.parent;
			}

			if(nextChild != null)
				return new DeltaVisit(this);

			if(parent != null)
				return new DeltaVisit(parent);
			return null;
		}
	}

	private void addObjectAndTrack(PackedObjectInfo oe) {
		entries[entryCount++] = oe;
		if(needNewObjectIds())
			newObjectIds.add(oe);
	}

	private class InflaterStream extends InputStream {
		private final Inflater inf;
		private final byte[] skipBuffer;
		private Source src;
		private long expectedSize;
		private long actualSize;
		private int p;

		InflaterStream() {
			inf = InflaterCache.get();
			skipBuffer = new byte[512];
		}

		void release() {
			inf.reset();
			InflaterCache.release(inf);
		}

		void open(Source source, long inflatedSize) throws IOException {
			src = source;
			expectedSize = inflatedSize;
			actualSize = 0;

			p = fill(src, 1);
			inf.setInput(buf, p, bAvail);
		}

		@Override
		public long skip(long toSkip) throws IOException {
			long n = 0;
			while(n < toSkip) {
				final int cnt = (int) Math.min(skipBuffer.length, toSkip - n);
				final int r = read(skipBuffer, 0, cnt);
				if(r <= 0)
					break;
				n += r;
			}
			return n;
		}

		@Override
		public int read() throws IOException {
			int n = read(skipBuffer, 0, 1);
			return n == 1 ? skipBuffer[0] & 0xff : -1;
		}

		@Override
		public int read(byte[] dst, int pos, int cnt) throws IOException {
			try {
				int n = 0;
				while(n < cnt) {
					int r = inf.inflate(dst, pos + n, cnt - n);
					n += r;
					if(inf.finished())
						break;
					if(inf.needsInput()) {
						onObjectData(src, buf, p, bAvail);
						use(bAvail);

						p = fill(src, 1);
						inf.setInput(buf, p, bAvail);
					} else if(r == 0) {
						throw new CorruptObjectException(MessageFormat.format(
								JGitText.get().packfileCorruptionDetected,
								JGitText.get().unknownZlibError));
					}
				}
				actualSize += n;
				return 0 < n ? n : -1;
			} catch(DataFormatException dfe) {
				throw new CorruptObjectException(MessageFormat.format(JGitText
						.get().packfileCorruptionDetected, dfe.getMessage()));
			}
		}

		@Override
		public void close() throws IOException {
			if(read(skipBuffer) != -1 || actualSize != expectedSize) {
				throw new CorruptObjectException(MessageFormat.format(JGitText
								.get().packfileCorruptionDetected,
						JGitText.get().wrongDecompressedLength));
			}

			int used = bAvail - inf.getRemaining();
			if(0 < used) {
				onObjectData(src, buf, p, used);
				use(used);
			}

			inf.reset();
		}
	}
}
