/*
 * Copyright (C) 2008, 2009, Google Inc.
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org>
 * Copyright (C) 2010, Matthias Sohn <matthias.sohn@sap.com>
 * Copyright (C) 2010, 2020, Christian Halstrick <christian.halstrick@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.dircache;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.Arrays;

import org.eclipse.jgit.dircache.DirCache.DirCacheVersion;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.util.IO;
import org.eclipse.jgit.util.MutableInteger;
import org.eclipse.jgit.util.NB;
import org.eclipse.jgit.util.SystemReader;

public class DirCacheEntry {
	private static final byte[] nullpad = new byte[8];

	public static final int STAGE_0 = 0;
	public static final int STAGE_1 = 1;
	public static final int STAGE_2 = 2;
	public static final int STAGE_3 = 3;

	private static final int P_MTIME = 8;
	private static final int P_MODE = 24;
	private static final int P_SIZE = 36;
	private static final int P_OBJECTID = 40;

	private static final int P_FLAGS = 60;
	private static final int P_FLAGS2 = 62;

	private static final int NAME_MASK = 0xfff;

	private static final int INTENT_TO_ADD = 0x20000000;
	private static final int SKIP_WORKTREE = 0x40000000;
	private static final int EXTENDED_FLAGS = (INTENT_TO_ADD | SKIP_WORKTREE);

	private static final int INFO_LEN = 62;
	private static final int INFO_LEN_EXTENDED = 64;

	private static final int EXTENDED = 0x40;
	private static final int ASSUME_VALID = 0x80;
	private static final int UPDATE_NEEDED = 0x1;

	private final byte[] info;
	private final int infoOffset;
	final byte[] path;
	private byte inCoreFlags;

	DirCacheEntry(byte[] sharedInfo, MutableInteger infoAt, InputStream in,
				  MessageDigest md, Instant smudge, DirCacheVersion version, DirCacheEntry previous) throws IOException {
		info = sharedInfo;
		infoOffset = infoAt.value;

		IO.readFully(in, info, infoOffset, INFO_LEN);

		int len;
		if(isExtended()) {
			len = INFO_LEN_EXTENDED;
			IO.readFully(in, info, infoOffset + INFO_LEN, INFO_LEN_EXTENDED - INFO_LEN);

			if((getExtendedFlags() & ~EXTENDED_FLAGS) != 0)
				throw new IOException(MessageFormat.format(JGitText.get()
						.DIRCUnrecognizedExtendedFlags, String.valueOf(getExtendedFlags())));
		} else
			len = INFO_LEN;

		infoAt.value += len;
		md.update(info, infoOffset, len);

		int toRemove = 0;
		if(version == DirCacheVersion.DIRC_VERSION_PATHCOMPRESS) {
			int b = in.read();
			md.update((byte) b);
			toRemove = b & 0x7F;
			while((b & 0x80) != 0) {
				toRemove++;
				b = in.read();
				md.update((byte) b);
				toRemove = (toRemove << 7) | (b & 0x7F);
			}
			if(toRemove < 0
					|| (previous != null && toRemove > previous.path.length)) {
				if(previous == null) {
					throw new IOException(MessageFormat.format(
							JGitText.get().DIRCCorruptLengthFirst,
							toRemove));
				}
				throw new IOException(MessageFormat.format(
						JGitText.get().DIRCCorruptLength,
						toRemove, previous.getPathString()));
			}
		}
		int pathLen = NB.decodeUInt16(info, infoOffset + P_FLAGS) & NAME_MASK;
		int skipped = 0;
		if(pathLen < NAME_MASK) {
			path = new byte[pathLen];
			if(version == DirCacheVersion.DIRC_VERSION_PATHCOMPRESS
					&& previous != null) {
				System.arraycopy(previous.path, 0, path, 0,
						previous.path.length - toRemove);
				IO.readFully(in, path, previous.path.length - toRemove,
						pathLen - (previous.path.length - toRemove));
				md.update(path, previous.path.length - toRemove,
						pathLen - (previous.path.length - toRemove));
				pathLen = pathLen - (previous.path.length - toRemove);
			} else {
				IO.readFully(in, path, 0, pathLen);
				md.update(path, 0, pathLen);
			}
		} else if(version != DirCacheVersion.DIRC_VERSION_PATHCOMPRESS
				|| previous == null || toRemove == previous.path.length) {
			ByteArrayOutputStream tmp = new ByteArrayOutputStream();
			byte[] buf = new byte[NAME_MASK];
			IO.readFully(in, buf, 0, NAME_MASK);
			tmp.write(buf);
			readNulTerminatedString(in, tmp);
			path = tmp.toByteArray();
			pathLen = path.length;
			md.update(path, 0, pathLen);
			skipped = 1;
			md.update((byte) 0);
		} else {
			ByteArrayOutputStream tmp = new ByteArrayOutputStream();
			tmp.write(previous.path, 0, previous.path.length - toRemove);
			pathLen = readNulTerminatedString(in, tmp);
			path = tmp.toByteArray();
			md.update(path, previous.path.length - toRemove, pathLen);
			skipped = 1;
			md.update((byte) 0);
		}

		try {
			checkPath(path);
		} catch(InvalidPathException e) {
			CorruptObjectException p =
					new CorruptObjectException(e.getMessage(), e.getCause());
			e.getCause();
			throw p;
		}

		if(version == DirCacheVersion.DIRC_VERSION_PATHCOMPRESS) {
			if(skipped == 0) {
				int b = in.read();
				if(b < 0) {
					throw new EOFException(JGitText.get().shortReadOfBlock);
				}
				md.update((byte) b);
			}
		} else {
			final int actLen = len + pathLen;
			final int expLen = (actLen + 8) & ~7;
			final int padLen = expLen - actLen - skipped;
			if(padLen > 0) {
				IO.skipFully(in, padLen);
				md.update(nullpad, 0, padLen);
			}
		}
		if(mightBeRacilyClean(smudge)) {
			smudgeRacilyClean();
		}
	}

	public DirCacheEntry(String newPath) {
		this(Constants.encode(newPath), STAGE_0);
	}

	public DirCacheEntry(String newPath, int stage) {
		this(Constants.encode(newPath), stage);
	}

	public DirCacheEntry(byte[] newPath) {
		this(newPath, STAGE_0);
	}

	public DirCacheEntry(byte[] path, int stage) {
		checkPath(path);
		if(stage < 0 || 3 < stage)
			throw new IllegalArgumentException(MessageFormat.format(JGitText.get().invalidStageForPath, stage, toString(path)));

		info = new byte[INFO_LEN];
		infoOffset = 0;
		this.path = path;

		int flags = ((stage & 0x3) << 12);
		flags |= Math.min(path.length, NAME_MASK);
		NB.encodeInt16(info, infoOffset + P_FLAGS, flags);
	}

	private int readNulTerminatedString(InputStream in, OutputStream out)
			throws IOException {
		int n = 0;
		for(; ; ) {
			int c = in.read();
			if(c < 0) {
				throw new EOFException(JGitText.get().shortReadOfBlock);
			}
			if(c == 0) {
				break;
			}
			out.write(c);
			n++;
		}
		return n;
	}

	void write(OutputStream os, DirCacheVersion version, DirCacheEntry previous)
			throws IOException {
		final int len = isExtended() ? INFO_LEN_EXTENDED : INFO_LEN;
		if(version != DirCacheVersion.DIRC_VERSION_PATHCOMPRESS) {
			os.write(info, infoOffset, len);
			os.write(path, 0, path.length);
			int entryLen = len + path.length;
			int expLen = (entryLen + 8) & ~7;
			if(entryLen != expLen)
				os.write(nullpad, 0, expLen - entryLen);
		} else {
			int pathCommon = 0;
			int toRemove;
			if(previous != null) {
				int pathLen = Math.min(path.length, previous.path.length);
				while(pathCommon < pathLen
						&& path[pathCommon] == previous.path[pathCommon]) {
					pathCommon++;
				}
				toRemove = previous.path.length - pathCommon;
			} else {
				toRemove = 0;
			}
			byte[] tmp = new byte[16];
			int n = tmp.length;
			tmp[--n] = (byte) (toRemove & 0x7F);
			while((toRemove >>>= 7) != 0) {
				tmp[--n] = (byte) (0x80 | (--toRemove & 0x7F));
			}
			os.write(info, infoOffset, len);
			os.write(tmp, n, tmp.length - n);
			os.write(path, pathCommon, path.length - pathCommon);
			os.write(0);
		}
	}

	public final boolean mightBeRacilyClean(Instant smudge) {
		final int base = infoOffset + P_MTIME;
		final int mtime = NB.decodeInt32(info, base);
		if((int) smudge.getEpochSecond() == mtime) {
			return smudge.getNano() <= NB.decodeInt32(info, base + 4);
		}
		return false;
	}

	public final void smudgeRacilyClean() {
		final int base = infoOffset + P_SIZE;
		Arrays.fill(info, base, base + 4, (byte) 0);
	}

	public final boolean isSmudged() {
		final int base = infoOffset + P_OBJECTID;
		return (getLength() == 0) && (Constants.EMPTY_BLOB_ID.compareTo(info, base) != 0);
	}

	final byte[] idBuffer() {
		return info;
	}

	final int idOffset() {
		return infoOffset + P_OBJECTID;
	}

	public boolean isAssumeValid() {
		return (info[infoOffset + P_FLAGS] & ASSUME_VALID) != 0;
	}

	public boolean isUpdateNeeded() {
		return (inCoreFlags & UPDATE_NEEDED) != 0;
	}

	public int getStage() {
		return (info[infoOffset + P_FLAGS] >>> 4) & 0x3;
	}

	public void setStage(int stage) {
		if((stage & ~0x3) != 0) {
			throw new IllegalArgumentException(
					"Invalid stage, must be in range [0..3]");
		}
		byte flags = info[infoOffset + P_FLAGS];
		info[infoOffset + P_FLAGS] = (byte) ((flags & 0xCF) | (stage << 4));
	}

	public boolean isSkipWorkTree() {
		return (getExtendedFlags() & SKIP_WORKTREE) != 0;
	}

	public boolean isMerged() {
		return getStage() == STAGE_0;
	}

	public int getRawMode() {
		return NB.decodeInt32(info, infoOffset + P_MODE);
	}

	public FileMode getFileMode() {
		return FileMode.fromBits(getRawMode());
	}

	public void setFileMode(FileMode mode) {
		switch(mode.getBits() & FileMode.TYPE_MASK) {
			case FileMode.TYPE_MISSING:
			case FileMode.TYPE_TREE:
				throw new IllegalArgumentException(MessageFormat.format(JGitText.get().invalidModeForPath, mode, getPathString()));
		}
		NB.encodeInt32(info, infoOffset + P_MODE, mode.getBits());
	}

	void setFileMode(int mode) {
		NB.encodeInt32(info, infoOffset + P_MODE, mode);
	}

	public Instant getLastModifiedInstant() {
		return decodeTSInstant();
	}

	public void setLastModified(Instant when) {
		encodeTS(when);
	}

	public int getLength() {
		return NB.decodeInt32(info, infoOffset + P_SIZE);
	}

	public void setLength(int sz) {
		NB.encodeInt32(info, infoOffset + P_SIZE, sz);
	}

	public void setLength(long sz) {
		setLength((int) sz);
	}

	public ObjectId getObjectId() {
		return ObjectId.fromRaw(idBuffer(), idOffset());
	}

	public void setObjectId(AnyObjectId id) {
		id.copyRawTo(idBuffer(), idOffset());
	}

	public void setObjectIdFromRaw(byte[] bs, int p) {
		final int n = Constants.OBJECT_ID_LENGTH;
		System.arraycopy(bs, p, idBuffer(), idOffset(), n);
	}

	public String getPathString() {
		return toString(path);
	}

	public byte[] getRawPath() {
		return path.clone();
	}

	@Override
	public String toString() {
		return getFileMode() + " " + getLength() + " "
				+ getLastModifiedInstant()
				+ " " + getObjectId() + " " + getStage() + " "
				+ getPathString() + "\n";
	}

	public void copyMetaData(DirCacheEntry src) {
		copyMetaData(src, false);
	}

	void copyMetaData(DirCacheEntry src, boolean keepStage) {
		int origflags = NB.decodeUInt16(info, infoOffset + P_FLAGS);
		int newflags = NB.decodeUInt16(src.info, src.infoOffset + P_FLAGS);
		System.arraycopy(src.info, src.infoOffset, info, infoOffset, INFO_LEN);
		final int pLen = origflags & NAME_MASK;
		final int SHIFTED_STAGE_MASK = 0x3 << 12;
		final int pStageShifted;
		if(keepStage)
			pStageShifted = origflags & SHIFTED_STAGE_MASK;
		else
			pStageShifted = newflags & SHIFTED_STAGE_MASK;
		NB.encodeInt16(info, infoOffset + P_FLAGS, pStageShifted | pLen
				| (newflags & ~NAME_MASK & ~SHIFTED_STAGE_MASK));
	}

	boolean isExtended() {
		return (info[infoOffset + P_FLAGS] & EXTENDED) != 0;
	}

	private Instant decodeTSInstant() {
		final int base = infoOffset + DirCacheEntry.P_MTIME;
		final int sec = NB.decodeInt32(info, base);
		final int nano = NB.decodeInt32(info, base + 4);
		return Instant.ofEpochSecond(sec, nano);
	}

	private void encodeTS(Instant when) {
		final int base = infoOffset + DirCacheEntry.P_MTIME;
		NB.encodeInt32(info, base, (int) when.getEpochSecond());
		NB.encodeInt32(info, base + 4, when.getNano());
	}

	private int getExtendedFlags() {
		if(isExtended()) {
			return NB.decodeUInt16(info, infoOffset + P_FLAGS2) << 16;
		}
		return 0;
	}

	private static void checkPath(byte[] path) {
		try {
			SystemReader.getInstance().checkPath(path);
		} catch(CorruptObjectException e) {
			InvalidPathException p = new InvalidPathException(toString(path));
			p.initCause(e);
			throw p;
		}
	}

	static String toString(byte[] path) {
		return UTF_8.decode(ByteBuffer.wrap(path)).toString();
	}

	static int getMaximumInfoLength(boolean extended) {
		return extended ? INFO_LEN_EXTENDED : INFO_LEN;
	}
}
