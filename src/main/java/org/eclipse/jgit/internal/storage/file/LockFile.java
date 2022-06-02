/*
 * Copyright (C) 2007, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2006-2021, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.file;

import static org.eclipse.jgit.lib.Constants.LOCK_SUFFIX;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FS.LockToken;
import org.eclipse.jgit.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LockFile {
	private static final Logger LOG = LoggerFactory.getLogger(LockFile.class);

	public static boolean unlock(File file) {
		final File lockFile = getLockFile(file);
		final int flags = FileUtils.RETRY | FileUtils.SKIP_MISSING;
		try {
			FileUtils.delete(lockFile, flags);
		} catch(IOException ignored) {
		}
		return !lockFile.exists();
	}

	static File getLockFile(File file) {
		return new File(file.getParentFile(),
				file.getName() + LOCK_SUFFIX);
	}

	static final FilenameFilter FILTER = (File dir, String name) -> !name.endsWith(LOCK_SUFFIX);

	private final File ref;
	private final File lck;
	private boolean haveLck;
	private FileOutputStream os;
	private boolean needSnapshot;
	private boolean fsync;
	private boolean isAppend;
	private boolean written;
	private boolean snapshotNoConfig;
	private FileSnapshot commitSnapshot;
	private LockToken token;

	public LockFile(File f) {
		ref = f;
		lck = getLockFile(ref);
	}

	public boolean lock() throws IOException {
		if(haveLck) {
			throw new IllegalStateException(
					MessageFormat.format(JGitText.get().lockAlreadyHeld, ref));
		}
		FileUtils.mkdirs(lck.getParentFile(), true);
		try {
			token = FS.DETECTED.createNewFileAtomic(lck);
		} catch(IOException e) {
			LOG.error(JGitText.get().failedCreateLockFile, lck, e);
			throw e;
		}
		boolean obtainedLock = token.isCreated();
		if(obtainedLock) {
			haveLck = true;
			isAppend = false;
			written = false;
		} else {
			closeToken();
		}
		return obtainedLock;
	}

	public boolean lockForAppend() throws IOException {
		if(!lock()) {
			return false;
		}
		copyCurrentContent();
		isAppend = true;
		written = false;
		return true;
	}

	private FileOutputStream getStream() throws IOException {
		return new FileOutputStream(lck, isAppend);
	}

	public void copyCurrentContent() throws IOException {
		requireLock();
		try(FileOutputStream out = getStream()) {
			try(FileInputStream fis = new FileInputStream(ref)) {
				if(fsync) {
					FileChannel in = fis.getChannel();
					long pos = 0;
					long cnt = in.size();
					while(0 < cnt) {
						long r = out.getChannel().transferFrom(in, pos, cnt);
						pos += r;
						cnt -= r;
					}
				} else {
					final byte[] buf = new byte[2048];
					int r;
					while((r = fis.read(buf)) >= 0) {
						out.write(buf, 0, r);
					}
				}
			} catch(FileNotFoundException fnfe) {
				if(ref.exists()) {
					throw fnfe;
				}
			}
		} catch(IOException | RuntimeException | Error ioe) {
			unlock();
			throw ioe;
		}
	}

	public void write(ObjectId id) throws IOException {
		byte[] buf = new byte[Constants.OBJECT_ID_STRING_LENGTH + 1];
		id.copyTo(buf, 0);
		buf[Constants.OBJECT_ID_STRING_LENGTH] = '\n';
		write(buf);
	}

	public void write(byte[] content) throws IOException {
		requireLock();
		try(FileOutputStream out = getStream()) {
			if(written) {
				throw new IOException(MessageFormat
						.format(JGitText.get().lockStreamClosed, ref));
			}
			if(fsync) {
				FileChannel fc = out.getChannel();
				ByteBuffer buf = ByteBuffer.wrap(content);
				while(0 < buf.remaining()) {
					fc.write(buf);
				}
				fc.force(true);
			} else {
				out.write(content);
			}
			written = true;
		} catch(IOException | RuntimeException | Error ioe) {
			unlock();
			throw ioe;
		}
	}

	public OutputStream getOutputStream() {
		requireLock();

		if(written || os != null)
			throw new IllegalStateException(MessageFormat.format(JGitText.get().lockStreamMultiple, ref));

		return new OutputStream() {

			private OutputStream out;
			private boolean closed;

			private OutputStream get() throws IOException {
				if(written) {
					throw new IOException(MessageFormat
							.format(JGitText.get().lockStreamMultiple, ref));
				}
				if(out == null) {
					os = getStream();
					if(fsync) {
						out = Channels.newOutputStream(os.getChannel());
					} else {
						out = os;
					}
				}
				return out;
			}

			@Override
			public void write(byte[] b, int o, int n) throws IOException {
				get().write(b, o, n);
			}

			@Override
			public void write(byte[] b) throws IOException {
				get().write(b);
			}

			@Override
			public void write(int b) throws IOException {
				get().write(b);
			}

			@Override
			public void close() throws IOException {
				if(closed) {
					return;
				}
				closed = true;
				try {
					if(written) {
						throw new IOException(MessageFormat
								.format(JGitText.get().lockStreamClosed, ref));
					}
					if(out != null) {
						if(fsync) {
							os.getChannel().force(true);
						}
						out.close();
						os = null;
					}
					written = true;
				} catch(IOException | RuntimeException | Error ioe) {
					unlock();
					throw ioe;
				}
			}
		};
	}

	void requireLock() {
		if(!haveLck) {
			unlock();
			throw new IllegalStateException(MessageFormat.format(JGitText.get().lockOnNotHeld, ref));
		}
	}

	public void setNeedStatInformation(boolean on) {
		setNeedSnapshot(on);
	}

	public void setNeedSnapshot(boolean on) {
		needSnapshot = on;
	}

	public void setNeedSnapshotNoConfig(boolean on) {
		needSnapshot = on;
		snapshotNoConfig = on;
	}

	public void setFSync(boolean on) {
		fsync = on;
	}

	public void waitForStatChange() throws InterruptedException {
		FileSnapshot o = FileSnapshot.save(ref);
		FileSnapshot n = FileSnapshot.save(lck);
		long fsTimeResolution = FS.getFileStoreAttributes(lck.toPath())
				.getFsTimestampResolution().toNanos();
		while(o.equals(n)) {
			TimeUnit.NANOSECONDS.sleep(fsTimeResolution);
			try {
				Files.setLastModifiedTime(lck.toPath(),
						FileTime.from(Instant.now()));
			} catch(IOException e) {
				n.waitUntilNotRacy();
			}
			n = FileSnapshot.save(lck);
		}
	}

	public boolean commit() {
		if(os != null) {
			unlock();
			throw new IllegalStateException(MessageFormat.format(JGitText.get().lockOnNotClosed, ref));
		}

		saveStatInformation();
		try {
			FileUtils.rename(lck, ref, StandardCopyOption.ATOMIC_MOVE);
			haveLck = false;
			isAppend = false;
			written = false;
			closeToken();
			return true;
		} catch(IOException e) {
			unlock();
			return false;
		}
	}

	private void closeToken() {
		if(token != null) {
			token.close();
			token = null;
		}
	}

	private void saveStatInformation() {
		if(needSnapshot) {
			commitSnapshot = snapshotNoConfig ? FileSnapshot.saveNoConfig(lck) : FileSnapshot.save(lck);
		}
	}

	@Deprecated
	public long getCommitLastModified() {
		return commitSnapshot.lastModified();
	}

	public FileSnapshot getCommitSnapshot() {
		return commitSnapshot;
	}

	public void createCommitSnapshot() {
		saveStatInformation();
	}

	public void unlock() {
		if(os != null) {
			try {
				os.close();
			} catch(IOException e) {
				LOG.error(MessageFormat
						.format(JGitText.get().unlockLockFileFailed, lck), e);
			}
			os = null;
		}

		if(haveLck) {
			haveLck = false;
			try {
				FileUtils.delete(lck, FileUtils.RETRY);
			} catch(IOException e) {
				LOG.error(MessageFormat
						.format(JGitText.get().unlockLockFileFailed, lck), e);
			} finally {
				closeToken();
			}
		}
		isAppend = false;
		written = false;
	}

	@Override
	public String toString() {
		return "LockFile[" + lck + ", haveLck=" + haveLck + "]";
	}
}
