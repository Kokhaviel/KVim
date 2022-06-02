/*
 * Copyright (C) 2008, 2010, Google Inc.
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org>
 * Copyright (C) 2011, 2020, Matthias Sohn <matthias.sohn@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.dircache;

import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.IndexReadException;
import org.eclipse.jgit.errors.LockFailedException;
import org.eclipse.jgit.events.IndexChangedEvent;
import org.eclipse.jgit.events.IndexChangedListener;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.internal.storage.file.FileSnapshot;
import org.eclipse.jgit.internal.storage.file.LockFile;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.lib.Config.ConfigEnum;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.TreeWalk.OperationType;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.eclipse.jgit.util.IO;
import org.eclipse.jgit.util.MutableInteger;
import org.eclipse.jgit.util.NB;
import org.eclipse.jgit.util.TemporaryBuffer;
import org.eclipse.jgit.util.io.SilentFileInputStream;

import java.io.*;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

public class DirCache {
	private static final byte[] SIG_DIRC = { 'D', 'I', 'R', 'C' };

	private static final int EXT_TREE = 0x54524545;

	private static final DirCacheEntry[] NO_ENTRIES = {};

	private static final byte[] NO_CHECKSUM = {};

	static final Comparator<DirCacheEntry> ENT_CMP = (DirCacheEntry o1,
			DirCacheEntry o2) -> {
		final int cr = cmp(o1, o2);
		if (cr != 0)
			return cr;
		return o1.getStage() - o2.getStage();
	};

	static int cmp(DirCacheEntry a, DirCacheEntry b) {
		return cmp(a.path, a.path.length, b);
	}

	static int cmp(byte[] aPath, int aLen, DirCacheEntry b) {
		return cmp(aPath, aLen, b.path, b.path.length);
	}

	static int cmp(final byte[] aPath, final int aLen, final byte[] bPath,
			final int bLen) {
		for (int cPos = 0; cPos < aLen && cPos < bLen; cPos++) {
			final int cmp = (aPath[cPos] & 0xff) - (bPath[cPos] & 0xff);
			if (cmp != 0)
				return cmp;
		}
		return aLen - bLen;
	}

	public static DirCache newInCore() {
		return new DirCache(null);
	}

	public static DirCache read(ObjectReader reader, AnyObjectId treeId)
			throws IOException {
		DirCache d = newInCore();
		DirCacheBuilder b = d.builder();
		b.addTree(null, DirCacheEntry.STAGE_0, reader, treeId);
		b.finish();
		return d;
	}

	public static DirCache read(Repository repository) throws IOException {
		final DirCache c = read(repository.getIndexFile());
		c.repository = repository;
		return c;
	}

	public static DirCache read(File indexLocation) throws IOException {
		final DirCache c = new DirCache(indexLocation);
		c.read();
		return c;
	}

	public static DirCache lock(File indexLocation) throws IOException {
		final DirCache c = new DirCache(indexLocation);
		if (!c.lock())
			throw new LockFailedException(indexLocation);

		try {
			c.read();
		} catch (IOException | RuntimeException | Error e) {
			c.unlock();
			throw e;
		}

		return c;
	}

	public static DirCache lock(final Repository repository, final IndexChangedListener indexChangedListener)
			throws IOException {
		DirCache c = lock(repository.getIndexFile(), indexChangedListener);
		c.repository = repository;
		return c;
	}

	public static DirCache lock(final File indexLocation, IndexChangedListener indexChangedListener) throws IOException {
		DirCache c = lock(indexLocation);
		c.registerIndexChangedListener(indexChangedListener);
		return c;
	}

	private final File liveFile;
	private DirCacheEntry[] sortedEntries;
	private int entryCnt;
	private DirCacheTree tree;
	private LockFile myLock;
	private FileSnapshot snapshot;
	private byte[] readIndexChecksum;
	private byte[] writeIndexChecksum;
	private IndexChangedListener indexChangedListener;
	private Repository repository;
	private DirCacheVersion version;

	public DirCache(File indexLocation) {
		liveFile = indexLocation;
		clear();
	}

	public DirCacheBuilder builder() {
		return new DirCacheBuilder(this, entryCnt + 16);
	}

	public DirCacheEditor editor() {
		return new DirCacheEditor(this, entryCnt + 16);
	}

	void replace(DirCacheEntry[] e, int cnt) {
		sortedEntries = e;
		entryCnt = cnt;
		tree = null;
	}

	public void read() throws IOException {
		if (liveFile == null)
			throw new IOException(JGitText.get().dirCacheDoesNotHaveABackingFile);
		if (!liveFile.exists())
			clear();
		else if (snapshot == null || snapshot.isModified(liveFile)) {
			try (SilentFileInputStream inStream = new SilentFileInputStream(
					liveFile)) {
				clear();
				readFrom(inStream);
			} catch (FileNotFoundException fnfe) {
				if (liveFile.exists()) {
					throw new IndexReadException(
							MessageFormat.format(JGitText.get().cannotReadIndex,
									liveFile.getAbsolutePath(), fnfe));
				}
				clear();
			}
			snapshot = FileSnapshot.save(liveFile);
		}
	}

	public void clear() {
		snapshot = null;
		sortedEntries = NO_ENTRIES;
		entryCnt = 0;
		tree = null;
		readIndexChecksum = NO_CHECKSUM;
	}

	private void readFrom(InputStream inStream) throws IOException {
		final BufferedInputStream in = new BufferedInputStream(inStream);
		final MessageDigest md = Constants.newMessageDigest();

		final byte[] hdr = new byte[20];
		IO.readFully(in, hdr, 0, 12);
		md.update(hdr, 0, 12);
		if (!is_DIRC(hdr))
			throw new CorruptObjectException(JGitText.get().notADIRCFile);
		int versionCode = NB.decodeInt32(hdr, 4);
		DirCacheVersion ver = DirCacheVersion.fromInt(versionCode);
		if (ver == null) {
			throw new CorruptObjectException(
					MessageFormat.format(JGitText.get().unknownDIRCVersion,
							versionCode));
		}
		boolean extended = false;
		switch (ver) {
		case DIRC_VERSION_MINIMUM:
			break;
		case DIRC_VERSION_EXTENDED:
		case DIRC_VERSION_PATHCOMPRESS:
			extended = true;
			break;
		default:
			throw new CorruptObjectException(MessageFormat
					.format(JGitText.get().unknownDIRCVersion, ver));
		}
		version = ver;
		entryCnt = NB.decodeInt32(hdr, 8);
		if (entryCnt < 0)
			throw new CorruptObjectException(JGitText.get().DIRCHasTooManyEntries);

		snapshot = FileSnapshot.save(liveFile);
		Instant smudge = snapshot.lastModifiedInstant();

		final int infoLength = DirCacheEntry.getMaximumInfoLength(extended);
		final byte[] infos = new byte[infoLength * entryCnt];
		sortedEntries = new DirCacheEntry[entryCnt];

		final MutableInteger infoAt = new MutableInteger();
		for (int i = 0; i < entryCnt; i++) {
			sortedEntries[i] = new DirCacheEntry(infos, infoAt, in, md, smudge,
					version, i == 0 ? null : sortedEntries[i - 1]);
		}

		for (;;) {
			in.mark(21);
			IO.readFully(in, hdr, 0, 20);
			if (in.read() < 0) {
				break;
			}

			in.reset();
			md.update(hdr, 0, 8);
			IO.skipFully(in, 8);

			long sz = NB.decodeUInt32(hdr, 4);
			if(NB.decodeInt32(hdr, 0) == EXT_TREE) {
				if(Integer.MAX_VALUE < sz) {
					throw new CorruptObjectException(MessageFormat.format(
							JGitText.get().DIRCExtensionIsTooLargeAt,
							formatExtensionName(hdr), sz));
				}
				final byte[] raw = new byte[(int) sz];
				IO.readFully(in, raw, 0, raw.length);
				md.update(raw, 0, raw.length);
				tree = new DirCacheTree(raw, new MutableInteger(), null);
			} else {
				if(hdr[0] >= 'A' && hdr[0] <= 'Z') {
					skipOptionalExtension(in, md, hdr, sz);
				} else {
					throw new CorruptObjectException(MessageFormat.format(JGitText.get().DIRCExtensionNotSupportedByThisVersion,
							formatExtensionName(hdr)));
				}
			}
		}

		readIndexChecksum = md.digest();
		if (!Arrays.equals(readIndexChecksum, hdr)) {
			throw new CorruptObjectException(JGitText.get().DIRCChecksumMismatch);
		}
	}

	private void skipOptionalExtension(final InputStream in,
			final MessageDigest md, final byte[] hdr, long sz)
			throws IOException {
		final byte[] b = new byte[4096];
		while (0 < sz) {
			int n = in.read(b, 0, (int) Math.min(b.length, sz));
			if (n < 0) {
				throw new EOFException(
						MessageFormat.format(JGitText.get().shortReadOfOptionalDIRCExtensionExpectedAnotherBytes,
								formatExtensionName(hdr), sz));
			}
			md.update(b, 0, n);
			sz -= n;
		}
	}

	private static String formatExtensionName(byte[] hdr) {
		return "'" + new String(hdr, 0, 4, ISO_8859_1) + "'";
	}

	private static boolean is_DIRC(byte[] hdr) {
		if (hdr.length < SIG_DIRC.length)
			return false;
		for (int i = 0; i < SIG_DIRC.length; i++)
			if (hdr[i] != SIG_DIRC[i])
				return false;
		return true;
	}

	public boolean lock() throws IOException {
		if (liveFile == null)
			throw new IOException(JGitText.get().dirCacheDoesNotHaveABackingFile);
		final LockFile tmp = new LockFile(liveFile);
		if (tmp.lock()) {
			tmp.setNeedStatInformation(true);
			myLock = tmp;
			return true;
		}
		return false;
	}

	public void write() throws IOException {
		final LockFile tmp = myLock;
		requireLocked(tmp);
		try (OutputStream o = tmp.getOutputStream();
				OutputStream bo = new BufferedOutputStream(o)) {
			writeTo(liveFile.getParentFile(), bo);
		} catch (IOException | RuntimeException | Error err) {
			tmp.unlock();
			throw err;
		}
	}

	void writeTo(File dir, OutputStream os) throws IOException {
		final MessageDigest foot = Constants.newMessageDigest();
		final DigestOutputStream dos = new DigestOutputStream(os, foot);

		if (version == null && this.repository != null) {
			DirCacheConfig config = repository.getConfig()
					.get(DirCacheConfig::new);
			version = config.getIndexVersion();
		}
		if (version == null
				|| version == DirCacheVersion.DIRC_VERSION_MINIMUM) {
			version = DirCacheVersion.DIRC_VERSION_MINIMUM;
			for (int i = 0; i < entryCnt; i++) {
				if (sortedEntries[i].isExtended()) {
					version = DirCacheVersion.DIRC_VERSION_EXTENDED;
					break;
				}
			}
		}

		final byte[] tmp = new byte[128];
		System.arraycopy(SIG_DIRC, 0, tmp, 0, SIG_DIRC.length);
		NB.encodeInt32(tmp, 4, version.getVersionCode());
		NB.encodeInt32(tmp, 8, entryCnt);
		dos.write(tmp, 0, 12);

		Instant smudge;
		if (myLock != null) {
			myLock.createCommitSnapshot();
			snapshot = myLock.getCommitSnapshot();
			smudge = snapshot.lastModifiedInstant();
		} else {
			smudge = Instant.EPOCH;
		}

		final boolean writeTree = tree != null;

		if (repository != null && entryCnt > 0)
			updateSmudgedEntries();

		for (int i = 0; i < entryCnt; i++) {
			final DirCacheEntry e = sortedEntries[i];
			if (e.mightBeRacilyClean(smudge)) {
				e.smudgeRacilyClean();
			}
			e.write(dos, version, i == 0 ? null : sortedEntries[i - 1]);
		}

		if (writeTree) {
			TemporaryBuffer bb = new TemporaryBuffer.LocalFile(dir, 5 << 20);
			try {
				tree.write(tmp, bb);
				bb.close();

				NB.encodeInt32(tmp, 0, EXT_TREE);
				NB.encodeInt32(tmp, 4, (int) bb.length());
				dos.write(tmp, 0, 8);
				bb.writeTo(dos, null);
			} finally {
				bb.destroy();
			}
		}
		writeIndexChecksum = foot.digest();
		os.write(writeIndexChecksum);
		os.close();
	}

	public boolean commit() {
		final LockFile tmp = myLock;
		requireLocked(tmp);
		myLock = null;
		if (!tmp.commit()) {
			return false;
		}
		snapshot = tmp.getCommitSnapshot();
		if (indexChangedListener != null
				&& !Arrays.equals(readIndexChecksum, writeIndexChecksum)) {
			indexChangedListener.onIndexChanged(new IndexChangedEvent());
		}
		return true;
	}

	private void requireLocked(LockFile tmp) {
		if (liveFile == null)
			throw new IllegalStateException(JGitText.get().dirCacheIsNotLocked);
		if (tmp == null)
			throw new IllegalStateException(MessageFormat.format(JGitText.get().dirCacheFileIsNotLocked
					, liveFile.getAbsolutePath()));
	}

	public void unlock() {
		final LockFile tmp = myLock;
		if (tmp != null) {
			myLock = null;
			tmp.unlock();
		}
	}

	public int findEntry(String path) {
		final byte[] p = Constants.encode(path);
		return findEntry(p, p.length);
	}

	public int findEntry(byte[] p, int pLen) {
		return findEntry(0, p, pLen);
	}

	int findEntry(int low, byte[] p, int pLen) {
		int high = entryCnt;
		while (low < high) {
			int mid = (low + high) >>> 1;
			final int cmp = cmp(p, pLen, sortedEntries[mid]);
			if (cmp < 0)
				high = mid;
			else if (cmp == 0) {
				while (mid > 0 && cmp(p, pLen, sortedEntries[mid - 1]) == 0)
					mid--;
				return mid;
			} else
				low = mid + 1;
		}
		return -(low + 1);
	}

	public int nextEntry(int position) {
		DirCacheEntry last = sortedEntries[position];
		int nextIdx = position + 1;
		while (nextIdx < entryCnt) {
			final DirCacheEntry next = sortedEntries[nextIdx];
			if (cmp(last, next) != 0)
				break;
			last = next;
			nextIdx++;
		}
		return nextIdx;
	}

	int nextEntry(byte[] p, int pLen, int nextIdx) {
		while (nextIdx < entryCnt) {
			final DirCacheEntry next = sortedEntries[nextIdx];
			if (!DirCacheTree.peq(p, next.path, pLen))
				break;
			nextIdx++;
		}
		return nextIdx;
	}

	public int getEntryCount() {
		return entryCnt;
	}

	public DirCacheEntry getEntry(int i) {
		return sortedEntries[i];
	}

	public DirCacheEntry getEntry(String path) {
		final int i = findEntry(path);
		return i < 0 ? null : sortedEntries[i];
	}

	void toArray(final int i, final DirCacheEntry[] dst, final int off,
			final int cnt) {
		System.arraycopy(sortedEntries, i, dst, off, cnt);
	}

	public DirCacheTree getCacheTree(boolean build) {
		if (build) {
			if (tree == null)
				tree = new DirCacheTree();
			tree.validate(sortedEntries, entryCnt, 0, 0);
		}
		return tree;
	}

	public ObjectId writeTree(ObjectInserter ow) throws IOException {
		return getCacheTree(true).writeTree(sortedEntries, 0, 0, ow);
	}

	public boolean hasUnmergedPaths() {
		for (int i = 0; i < entryCnt; i++) {
			if (sortedEntries[i].getStage() > 0) {
				return true;
			}
		}
		return false;
	}

	private void registerIndexChangedListener(IndexChangedListener listener) {
		this.indexChangedListener = listener;
	}

	private void updateSmudgedEntries() throws IOException {
		List<String> paths = new ArrayList<>(128);
		try (TreeWalk walk = new TreeWalk(repository)) {
			walk.setOperationType(OperationType.CHECKIN_OP);
			for (int i = 0; i < entryCnt; i++)
				if (sortedEntries[i].isSmudged())
					paths.add(sortedEntries[i].getPathString());
			if (paths.isEmpty())
				return;
			walk.setFilter(PathFilterGroup.createFromStrings(paths));

			DirCacheIterator iIter = new DirCacheIterator(this);
			FileTreeIterator fIter = new FileTreeIterator(repository);
			walk.addTree(iIter);
			walk.addTree(fIter);
			fIter.setDirCacheIterator(walk, 0);
			walk.setRecursive(true);
			while (walk.next()) {
				iIter = walk.getTree(0);
				if (iIter == null)
					continue;
				fIter = walk.getTree(1);
				if (fIter == null)
					continue;
				DirCacheEntry entry = iIter.getDirCacheEntry();
				if (entry.isSmudged() && iIter.idEqual(fIter)) {
					entry.setLength(fIter.getEntryLength());
					entry.setLastModified(fIter.getEntryLastModifiedInstant());
				}
			}
		}
	}

	enum DirCacheVersion implements ConfigEnum {

		DIRC_VERSION_MINIMUM(2),
		DIRC_VERSION_EXTENDED(3),
		DIRC_VERSION_PATHCOMPRESS(4);

		private final int version;

		DirCacheVersion(int versionCode) {
			this.version = versionCode;
		}

		public int getVersionCode() {
			return version;
		}

		@Override
		public String toConfigValue() {
			return Integer.toString(version);
		}

		@Override
		public boolean matchConfigValue(String in) {
			try {
				return version == Integer.parseInt(in);
			} catch (NumberFormatException e) {
				return false;
			}
		}

		public static DirCacheVersion fromInt(int val) {
			for (DirCacheVersion v : DirCacheVersion.values()) {
				if (val == v.getVersionCode()) {
					return v;
				}
			}
			return null;
		}
	}

	private static class DirCacheConfig {

		private final DirCacheVersion indexVersion;

		public DirCacheConfig(Config cfg) {
			boolean manyFiles = cfg.getBoolean(ConfigConstants.CONFIG_FEATURE_SECTION, ConfigConstants.CONFIG_KEY_MANYFILES, false);
			indexVersion = cfg.getEnum(DirCacheVersion.values(), ConfigConstants.CONFIG_INDEX_SECTION, null, ConfigConstants.CONFIG_KEY_VERSION,
					manyFiles ? DirCacheVersion.DIRC_VERSION_PATHCOMPRESS : DirCacheVersion.DIRC_VERSION_EXTENDED);
		}

		public DirCacheVersion getIndexVersion() {
			return indexVersion;
		}

	}
}
