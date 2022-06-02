/*
 * Copyright (C) 2017, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.reftable;

import org.eclipse.jgit.annotations.*;
import org.eclipse.jgit.internal.*;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.util.*;

import java.io.*;
import java.text.*;
import java.util.*;
import java.util.zip.*;

import static java.lang.Math.log;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.jgit.internal.storage.reftable.BlockWriter.*;
import static org.eclipse.jgit.internal.storage.reftable.ReftableConstants.*;
import static org.eclipse.jgit.lib.Constants.*;

public class ReftableWriter {
	private ReftableConfig config;
	private int refBlockSize, logBlockSize, restartInterval, maxIndexLevels, objIdLen;
	private boolean alignBlocks, indexObjects;
	private long minUpdateIndex, maxUpdateIndex;
	private final OutputStream outputStream;
	private ReftableOutputStream out;
	private ObjectIdSubclassMap<RefList> obj2ref;
	private Entry lastRef, lastLog;
	private BlockWriter cur;
	private Section refs, objs, logs;
	private Stats stats;

	public ReftableWriter(OutputStream os) {
		this(new ReftableConfig(), os);
		lastRef = lastLog = null;
	}

	public ReftableWriter(ReftableConfig cfg, OutputStream os) {
		config = cfg;
		outputStream = os;
	}

	public ReftableWriter setConfig(ReftableConfig cfg) {
		this.config = cfg != null ? cfg : new ReftableConfig();
		return this;
	}

	public ReftableWriter setMinUpdateIndex(long min) {
		minUpdateIndex = min;
		return this;
	}

	public ReftableWriter setMaxUpdateIndex(long max) {
		maxUpdateIndex = max;
		return this;
	}

	public ReftableWriter begin() {
		if(out != null) throw new IllegalStateException("begin() called twice.");

		refBlockSize = config.getRefBlockSize();
		logBlockSize = config.getLogBlockSize();
		restartInterval = config.getRestartInterval();
		maxIndexLevels = config.getMaxIndexLevels();
		alignBlocks = config.isAlignBlocks();
		indexObjects = config.isIndexObjects();

		if(refBlockSize <= 0)refBlockSize = 4 << 10;
		else if(refBlockSize > MAX_BLOCK_SIZE) throw new IllegalArgumentException();
		if(logBlockSize <= 0) logBlockSize = 2 * refBlockSize;
		if(restartInterval <= 0) restartInterval = refBlockSize < (60 << 10) ? 16 : 64;
		out = new ReftableOutputStream(outputStream, refBlockSize, alignBlocks);
		refs = new Section(REF_BLOCK_TYPE);
		if(indexObjects) obj2ref = new ObjectIdSubclassMap<>();
		writeFileHeader();
		return this;
	}

	public ReftableWriter sortAndWriteRefs(Collection<Ref> refsToPack) throws IOException {
		Iterator<RefEntry> itr = refsToPack.stream()
				.map(r -> new RefEntry(r, maxUpdateIndex - minUpdateIndex)).sorted(Entry::compare).iterator();
		RefEntry last = null;
		while(itr.hasNext()) {
			RefEntry entry = itr.next();
			if(last != null && Entry.compare(last, entry) == 0) throwIllegalEntry(last, entry);

			long blockPos = refs.write(entry);
			indexRef(entry.ref, blockPos);
			last = entry;
		}
		return this;
	}

	public void writeRef(Ref ref) throws IOException {
		writeRef(ref, maxUpdateIndex);
	}

	public void writeRef(Ref ref, long updateIndex) throws IOException {
		if(updateIndex < minUpdateIndex) {
			throw new IllegalArgumentException();
		}
		long d = updateIndex - minUpdateIndex;
		RefEntry entry = new RefEntry(ref, d);
		if(lastRef != null && Entry.compare(lastRef, entry) >= 0) throwIllegalEntry(lastRef, entry);

		lastRef = entry;
		long blockPos = refs.write(entry);
		indexRef(ref, blockPos);
	}

	private void throwIllegalEntry(Entry last, Entry now) {
		throw new IllegalArgumentException(MessageFormat.format(JGitText.get().reftableRecordsMustIncrease,
				new String(last.key, UTF_8), new String(now.key, UTF_8)));
	}

	private void indexRef(Ref ref, long blockPos) {
		if(indexObjects && !ref.isSymbolic()) {
			indexId(ref.getObjectId(), blockPos);
			indexId(ref.getPeeledObjectId(), blockPos);
		}
	}

	private void indexId(ObjectId id, long blockPos) {
		if(id != null) {
			RefList l = obj2ref.get(id);
			if(l == null) {
				l = new RefList(id);
				obj2ref.add(l);
			}
			l.addBlock(blockPos);
		}
	}

	public void writeLog(String ref, long updateIndex, PersonIdent who,
						 ObjectId oldId, ObjectId newId, @Nullable String message) throws IOException {
		String msg = message != null ? message : "";
		beginLog();
		LogEntry entry = new LogEntry(ref, updateIndex, who, oldId, newId, msg);
		if(lastLog != null && Entry.compare(lastLog, entry) >= 0) throwIllegalEntry(lastLog, entry);
		lastLog = entry;
		logs.write(entry);
	}

	public void deleteLog(String ref, long updateIndex) throws IOException {
		beginLog();
		logs.write(new DeleteLogEntry(ref, updateIndex));
	}

	private void beginLog() throws IOException {
		if(logs == null) {
			finishRefAndObjSections();
			out.flushFileHeader();
			out.setBlockSize(logBlockSize);
			logs = new Section(LOG_BLOCK_TYPE);
		}
	}

	public ReftableWriter finish() throws IOException {
		finishRefAndObjSections();
		finishLogSection();
		writeFileFooter();
		out.finishFile();

		stats = new Stats(this, out);
		out = null;
		obj2ref = null;
		cur = null;
		refs = null;
		objs = null;
		logs = null;
		return this;
	}

	private void finishRefAndObjSections() throws IOException {
		if(cur != null && cur.blockType() == REF_BLOCK_TYPE) {
			refs.finishSectionMaybeWriteIndex();
			if(indexObjects && !obj2ref.isEmpty() && refs.idx.bytes > 0) writeObjBlocks();
			obj2ref = null;
		}
	}

	private void writeObjBlocks() throws IOException {
		List<RefList> sorted = sortById(obj2ref);
		obj2ref = null;
		objIdLen = shortestUniqueAbbreviation(sorted);

		out.padBetweenBlocksToNextBlock();
		objs = new Section(OBJ_BLOCK_TYPE);
		objs.entryCnt = sorted.size();
		for(RefList l : sorted) objs.write(new ObjEntry(objIdLen, l, l.blockPos));
		objs.finishSectionMaybeWriteIndex();
	}

	private void finishLogSection() throws IOException {
		if(cur != null && cur.blockType() == LOG_BLOCK_TYPE) {
			logs.finishSectionMaybeWriteIndex();
		}
	}

	private boolean shouldHaveIndex(IndexBuilder idx) {
		int threshold;
		if(idx == refs.idx && alignBlocks) {
			threshold = 4;
		} else {
			threshold = 1;
		}
		return idx.entries.size() + (cur != null ? 1 : 0) > threshold;
	}

	private void writeFileHeader() {
		byte[] hdr = new byte[FILE_HEADER_LEN];
		encodeHeader(hdr);
		out.write(hdr, 0, FILE_HEADER_LEN);
	}

	private void encodeHeader(byte[] hdr) {
		System.arraycopy(FILE_HEADER_MAGIC, 0, hdr, 0, 4);
		int bs = alignBlocks ? refBlockSize : 0;
		NB.encodeInt32(hdr, 4, (VERSION_1 << 24) | bs);
		NB.encodeInt64(hdr, 8, minUpdateIndex);
		NB.encodeInt64(hdr, 16, maxUpdateIndex);
	}

	private void writeFileFooter() {
		int ftrLen = FILE_FOOTER_LEN;
		byte[] ftr = new byte[ftrLen];
		encodeHeader(ftr);

		NB.encodeInt64(ftr, 24, indexPosition(refs));
		NB.encodeInt64(ftr, 32, (firstBlockPosition(objs) << 5) | objIdLen);
		NB.encodeInt64(ftr, 40, indexPosition(objs));
		NB.encodeInt64(ftr, 48, firstBlockPosition(logs));
		NB.encodeInt64(ftr, 56, indexPosition(logs));

		CRC32 crc = new CRC32();
		crc.update(ftr, 0, ftrLen - 4);
		NB.encodeInt32(ftr, ftrLen - 4, (int) crc.getValue());

		out.write(ftr, 0, ftrLen);
	}

	private static long firstBlockPosition(@Nullable Section s) {
		return s != null ? s.firstBlockPosition : 0;
	}

	private static long indexPosition(@Nullable Section s) {
		return s != null && s.idx != null ? s.idx.rootPosition : 0;
	}

	public Stats getStats() {
		return stats;
	}

	public static class Stats {
		private final int refBlockSize;
		private final long minUpdateIndex, maxUpdateIndex, refCnt, logCnt, totalBytes;

		Stats(ReftableWriter w, ReftableOutputStream o) {
			refBlockSize = w.refBlockSize;

			minUpdateIndex = w.minUpdateIndex;
			maxUpdateIndex = w.maxUpdateIndex;
			o.paddingUsed();
			totalBytes = o.size();

			refCnt = w.refs.entryCnt;

			logCnt = w.logs != null ? w.logs.entryCnt : 0;
		}

		public int refBlockSize() {
			return refBlockSize;
		}

		public long minUpdateIndex() {
			return minUpdateIndex;
		}

		public long maxUpdateIndex() {
			return maxUpdateIndex;
		}

		public long refCount() {
			return refCnt;
		}

		public long logCount() {
			return logCnt;
		}

		public long totalBytes() {
			return totalBytes;
		}

	}

	private static List<RefList> sortById(ObjectIdSubclassMap<RefList> m) {
		List<RefList> s = new ArrayList<>(m.size());
		for(RefList l : m) {
			s.add(l);
		}
		Collections.sort(s);
		return s;
	}

	private static int shortestUniqueAbbreviation(List<RefList> in) {
		int bytes = Math.max(2, (int) (log(in.size()) / log(8)));
		Set<AbbreviatedObjectId> tmp = new HashSet<>((int) (in.size() * 0.75f));
		retry:
		for(; ; ) {
			int hexLen = bytes * 2;
			for(ObjectId id : in) {
				AbbreviatedObjectId a = id.abbreviate(hexLen);
				if(!tmp.add(a)) {
					if(++bytes >= OBJECT_ID_LENGTH) {
						return OBJECT_ID_LENGTH;
					}
					tmp.clear();
					continue retry;
				}
			}
			return bytes;
		}
	}

	private static class RefList extends ObjectIdOwnerMap.Entry {
		final LongList blockPos = new LongList(2);

		RefList(AnyObjectId id) {
			super(id);
		}

		void addBlock(long pos) {
			if(!blockPos.contains(pos)) blockPos.add(pos);
		}
	}

	private class Section {
		final IndexBuilder idx;
		final long firstBlockPosition;
		long entryCnt, bytes;

		Section(byte keyType) {
			idx = new IndexBuilder(keyType);
			firstBlockPosition = out.size();
		}

		long write(BlockWriter.Entry entry) throws IOException {
			if(cur == null) {
				beginBlock(entry);
			} else if(!cur.tryAdd(entry)) {
				flushCurBlock();
				if(cur.padBetweenBlocks()) {
					out.padBetweenBlocksToNextBlock();
				}
				beginBlock(entry);
			}
			entryCnt++;
			return out.size();
		}

		private void beginBlock(BlockWriter.Entry entry) throws BlockSizeTooSmallException {
			byte blockType = entry.blockType();
			int bs = out.bytesAvailableInBlock();
			cur = new BlockWriter(blockType, idx.keyType, bs, restartInterval);
			cur.mustAdd(entry);
		}

		void flushCurBlock() throws IOException {
			idx.entries.add(new IndexEntry(cur.lastKey(), out.size()));
			cur.writeTo(out);
		}

		void finishSectionMaybeWriteIndex() throws IOException {
			flushCurBlock();
			cur = null;
			if(shouldHaveIndex(idx)) {
				idx.writeIndex();
			}
			bytes = out.size() - firstBlockPosition;
		}
	}

	private class IndexBuilder {
		final byte keyType;
		List<IndexEntry> entries = new ArrayList<>();
		long rootPosition;
		int bytes, levels;

		IndexBuilder(byte kt) {
			keyType = kt;
		}

		void writeIndex() throws IOException {
			if(padBetweenBlocks(keyType)) out.padBetweenBlocksToNextBlock();
			long startPos = out.size();
			writeMultiLevelIndex(entries);
			bytes = (int) (out.size() - startPos);
			entries = null;
		}

		private void writeMultiLevelIndex(List<IndexEntry> keys)
				throws IOException {
			levels = 1;
			while(maxIndexLevels == 0 || levels < maxIndexLevels) {
				keys = writeOneLevel(keys);
				if(keys == null) {
					return;
				}
				levels++;
			}

			BlockWriter b = new BlockWriter(INDEX_BLOCK_TYPE, keyType, MAX_BLOCK_SIZE, Math.max(restartInterval, keys.size() / MAX_RESTARTS));
			for(Entry e : keys) {
				b.mustAdd(e);
			}
			rootPosition = out.size();
			b.writeTo(out);
		}

		private List<IndexEntry> writeOneLevel(List<IndexEntry> keys)
				throws IOException {
			Section thisLevel = new Section(keyType);
			for(Entry e : keys) {
				thisLevel.write(e);
			}
			if(!thisLevel.idx.entries.isEmpty()) {
				thisLevel.flushCurBlock();
				if(cur.padBetweenBlocks()) out.padBetweenBlocksToNextBlock();
				cur = null;
				return thisLevel.idx.entries;
			}

			rootPosition = out.size();
			cur.writeTo(out);
			cur = null;
			return null;
		}
	}
}
