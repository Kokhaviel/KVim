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

package org.eclipse.jgit.internal.storage.file;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import org.eclipse.jgit.errors.LockFailedException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.CoreConfig;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.storage.pack.PackConfig;
import org.eclipse.jgit.transport.PackLock;
import org.eclipse.jgit.transport.PackParser;
import org.eclipse.jgit.transport.PackedObjectInfo;
import org.eclipse.jgit.util.FileUtils;
import org.eclipse.jgit.util.NB;

public class ObjectDirectoryPackParser extends PackParser {
	private final FileObjectDatabase db;

	private final CRC32 crc;
	private final MessageDigest tailDigest;
	private final int indexVersion;
	private boolean keepEmpty;
	private File tmpPack;
	private File tmpIdx;
	private RandomAccessFile out;
	private long origEnd;
	private byte[] origHash;
	private long packEnd;
	private byte[] packHash;
	private Deflater def;
	private Pack newPack;

	private final PackConfig pconfig;

	ObjectDirectoryPackParser(FileObjectDatabase odb, InputStream src) {
		super(odb, src);
		this.db = odb;
		this.pconfig = new PackConfig(odb.getConfig());
		this.crc = new CRC32();
		this.tailDigest = Constants.newMessageDigest();

		indexVersion = db.getConfig().get(CoreConfig.KEY).getPackIndexVersion();
	}

	public Pack getPack() {
		return newPack;
	}

	@Override
	public long getPackSize() {
		if(newPack == null)
			return super.getPackSize();

		File pack = newPack.getPackFile();
		long size = pack.length();
		String p = pack.getAbsolutePath();
		String i = p.substring(0, p.length() - ".pack".length()) + ".idx";
		File idx = new File(i);
		if(idx.isFile())
			size += idx.length();
		return size;
	}

	@Override
	public PackLock parse(ProgressMonitor receiving, ProgressMonitor resolving)
			throws IOException {
		tmpPack = File.createTempFile("incoming_", ".pack", db.getDirectory());
		tmpIdx = new File(db.getDirectory(), baseName(tmpPack) + ".idx");
		try {
			out = new RandomAccessFile(tmpPack, "rw");

			super.parse(receiving, resolving);

			out.seek(packEnd);
			out.write(packHash);
			out.getChannel().force(true);
			out.close();

			writeIdx();

			tmpPack.setReadOnly();
			tmpIdx.setReadOnly();

			return renameAndOpenPack(getLockMessage());
		} finally {
			if(def != null)
				def.end();
			try {
				if(out != null && out.getChannel().isOpen())
					out.close();
			} catch(IOException ignored) {
			}
			cleanupTemporaryFiles();
		}
	}

	@Override
	protected void onPackHeader(long objectCount) {
	}

	@Override
	protected void onBeginWholeObject(long streamPosition, int type, long inflatedSize) {
		crc.reset();
	}

	@Override
	protected void onEndWholeObject(PackedObjectInfo info) {
		info.setCRC((int) crc.getValue());
	}

	@Override
	protected void onBeginOfsDelta(long streamPosition,
								   long baseStreamPosition, long inflatedSize) {
		crc.reset();
	}

	@Override
	protected void onBeginRefDelta(long streamPosition, AnyObjectId baseId,
								   long inflatedSize) {
		crc.reset();
	}

	@Override
	protected UnresolvedDelta onEndDelta() {
		UnresolvedDelta delta = new UnresolvedDelta();
		delta.setCRC((int) crc.getValue());
		return delta;
	}

	@Override
	protected void onInflatedObjectData(PackedObjectInfo obj, int typeCode, byte[] data) {
	}

	@Override
	protected void onObjectHeader(Source src, byte[] raw, int len) {
		crc.update(raw, 0, len);
	}

	@Override
	protected void onObjectData(Source src, byte[] raw, int pos, int len) {
		crc.update(raw, pos, len);
	}

	@Override
	protected void onStoreStream(byte[] raw, int len)
			throws IOException {
		out.write(raw, 0, len);
	}

	@Override
	protected void onPackFooter(byte[] hash) throws IOException {
		packEnd = out.getFilePointer();
		origEnd = packEnd;
		origHash = hash;
		packHash = hash;
	}

	@Override
	protected ObjectTypeAndSize seekDatabase(UnresolvedDelta delta,
											 ObjectTypeAndSize info) throws IOException {
		out.seek(delta.getOffset());
		crc.reset();
		return readObjectHeader(info);
	}

	@Override
	protected ObjectTypeAndSize seekDatabase(PackedObjectInfo obj,
											 ObjectTypeAndSize info) throws IOException {
		out.seek(obj.getOffset());
		crc.reset();
		return readObjectHeader(info);
	}

	@Override
	protected int readDatabase(byte[] dst, int pos, int cnt) throws IOException {
		return out.read(dst, pos, cnt);
	}

	@Override
	protected boolean checkCRC(int oldCRC) {
		return oldCRC == (int) crc.getValue();
	}

	private static String baseName(File tmpPack) {
		String name = tmpPack.getName();
		return name.substring(0, name.lastIndexOf('.'));
	}

	private void cleanupTemporaryFiles() {
		if(tmpIdx != null && !tmpIdx.delete() && tmpIdx.exists())
			tmpIdx.deleteOnExit();
		if(tmpPack != null && !tmpPack.delete() && tmpPack.exists())
			tmpPack.deleteOnExit();
	}

	@Override
	protected boolean onAppendBase(final int typeCode, final byte[] data,
								   final PackedObjectInfo info) throws IOException {
		info.setOffset(packEnd);

		final byte[] buf = buffer();
		int sz = data.length;
		int len = 0;
		buf[len++] = (byte) ((typeCode << 4) | (sz & 15));
		sz >>>= 4;
		while(sz > 0) {
			buf[len - 1] |= (byte) 0x80;
			buf[len++] = (byte) (sz & 0x7f);
			sz >>>= 7;
		}

		tailDigest.update(buf, 0, len);
		crc.reset();
		crc.update(buf, 0, len);
		out.seek(packEnd);
		out.write(buf, 0, len);
		packEnd += len;

		if(def == null)
			def = new Deflater(Deflater.DEFAULT_COMPRESSION, false);
		else
			def.reset();
		def.setInput(data);
		def.finish();

		while(!def.finished()) {
			len = def.deflate(buf);
			tailDigest.update(buf, 0, len);
			crc.update(buf, 0, len);
			out.write(buf, 0, len);
			packEnd += len;
		}

		info.setCRC((int) crc.getValue());
		return true;
	}

	@Override
	protected void onEndThinPack() throws IOException {
		final byte[] buf = buffer();

		final MessageDigest origDigest = Constants.newMessageDigest();
		final MessageDigest tailDigest2 = Constants.newMessageDigest();
		final MessageDigest packDigest = Constants.newMessageDigest();

		long origRemaining = origEnd;
		out.seek(0);
		out.readFully(buf, 0, 12);
		origDigest.update(buf, 0, 12);
		origRemaining -= 12;

		NB.encodeInt32(buf, 8, getObjectCount());
		out.seek(0);
		out.write(buf, 0, 12);
		packDigest.update(buf, 0, 12);

		for(; ; ) {
			final int n = out.read(buf);
			if(n < 0)
				break;
			if(origRemaining != 0) {
				final int origCnt = (int) Math.min(n, origRemaining);
				origDigest.update(buf, 0, origCnt);
				origRemaining -= origCnt;
				if(origRemaining == 0)
					tailDigest2.update(buf, origCnt, n - origCnt);
			} else
				tailDigest2.update(buf, 0, n);

			packDigest.update(buf, 0, n);
		}

		if(!Arrays.equals(origDigest.digest(), origHash) || !Arrays
				.equals(tailDigest2.digest(), this.tailDigest.digest()))
			throw new IOException(
					JGitText.get().packCorruptedWhileWritingToFilesystem);

		packHash = packDigest.digest();
	}

	private void writeIdx() throws IOException {
		List<PackedObjectInfo> list = getSortedObjectList(null);
		try(FileOutputStream os = new FileOutputStream(tmpIdx)) {
			final PackIndexWriter iw;
			if(indexVersion <= 0)
				iw = PackIndexWriter.createOldestPossible(os, list);
			else
				iw = PackIndexWriter.createVersion(os, indexVersion);
			iw.write(list, packHash);
			os.getChannel().force(true);
		}
	}

	private PackLock renameAndOpenPack(String lockMessage)
			throws IOException {
		if(!keepEmpty && getObjectCount() == 0) {
			cleanupTemporaryFiles();
			return null;
		}

		final MessageDigest d = Constants.newMessageDigest();
		final byte[] oeBytes = new byte[Constants.OBJECT_ID_LENGTH];
		for(int i = 0; i < getObjectCount(); i++) {
			final PackedObjectInfo oe = getObject(i);
			oe.copyRawTo(oeBytes, 0);
			d.update(oeBytes);
		}

		ObjectId id = ObjectId.fromRaw(d.digest());
		File packDir = new File(db.getDirectory(), "pack");
		PackFile finalPack = new PackFile(packDir, id, PackExt.PACK);
		PackFile finalIdx = finalPack.create(PackExt.INDEX);
		final PackLockImpl keep = new PackLockImpl(finalPack, db.getFS());

		if(!packDir.exists() && !packDir.mkdir() && !packDir.exists()) {
			cleanupTemporaryFiles();
			throw new IOException(MessageFormat.format(
					JGitText.get().cannotCreateDirectory, packDir
							.getAbsolutePath()));
		}

		if(finalPack.exists()) {
			cleanupTemporaryFiles();
			return null;
		}

		if(lockMessage != null) {
			try {
				if(!keep.lock(lockMessage))
					throw new LockFailedException(
							MessageFormat.format(
									JGitText.get().cannotLockPackIn, finalPack));
			} catch(IOException e) {
				cleanupTemporaryFiles();
				throw e;
			}
		}

		try {
			FileUtils.rename(tmpPack, finalPack,
					StandardCopyOption.ATOMIC_MOVE);
		} catch(IOException e) {
			cleanupTemporaryFiles();
			keep.unlock();
			throw new IOException(MessageFormat.format(
					JGitText.get().cannotMovePackTo, finalPack), e);
		}

		try {
			FileUtils.rename(tmpIdx, finalIdx, StandardCopyOption.ATOMIC_MOVE);
		} catch(IOException e) {
			cleanupTemporaryFiles();
			keep.unlock();
			if(!finalPack.delete())
				finalPack.deleteOnExit();
			throw new IOException(MessageFormat.format(
					JGitText.get().cannotMoveIndexTo, finalIdx), e);
		}

		boolean interrupted = false;
		try {
			FileSnapshot snapshot = FileSnapshot.save(finalPack);
			if(pconfig.doWaitPreventRacyPack(snapshot.size())) {
				snapshot.waitUntilNotRacy();
			}
		} catch(InterruptedException e) {
			interrupted = true;
		}
		try {
			newPack = db.openPack(finalPack);
		} catch(IOException err) {
			keep.unlock();
			if(finalPack.exists())
				FileUtils.delete(finalPack);
			if(finalIdx.exists())
				FileUtils.delete(finalIdx);
			throw err;
		} finally {
			if(interrupted) {
				Thread.currentThread().interrupt();
			}
		}

		return lockMessage != null ? keep : null;
	}
}
