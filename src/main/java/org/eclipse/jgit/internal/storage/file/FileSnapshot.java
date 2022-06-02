/*
 * Copyright (C) 2010, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.file;

import static org.eclipse.jgit.util.FS.FileStoreAttributes.FALLBACK_FILESTORE_ATTRIBUTES;
import static org.eclipse.jgit.util.FS.FileStoreAttributes.FALLBACK_TIMESTAMP_RESOLUTION;

import java.io.File;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FS.FileStoreAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileSnapshot {
	private static final Logger LOG = LoggerFactory.getLogger(FileSnapshot.class);
	public static final long UNKNOWN_SIZE = -1;
	private static final Instant UNKNOWN_TIME = Instant.ofEpochMilli(-1);
	private static final Object MISSING_FILEKEY = new Object();
	private static final DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.nnnnnnnnn")
			.withLocale(Locale.getDefault()).withZone(ZoneId.systemDefault());

	public static final FileSnapshot DIRTY = new FileSnapshot(UNKNOWN_TIME,
			UNKNOWN_TIME, UNKNOWN_SIZE, Duration.ZERO, MISSING_FILEKEY);

	public static final FileSnapshot MISSING_FILE = new FileSnapshot(
			Instant.EPOCH, Instant.EPOCH, 0, Duration.ZERO, MISSING_FILEKEY) {
		@Override
		public boolean isModified(File path) {
			return FS.DETECTED.exists(path);
		}
	};

	public static FileSnapshot save(File path) {
		return new FileSnapshot(path);
	}

	public static FileSnapshot saveNoConfig(File path) {
		return new FileSnapshot(path, false);
	}

	private static Object getFileKey(BasicFileAttributes fileAttributes) {
		Object fileKey = fileAttributes.fileKey();
		return fileKey == null ? MISSING_FILEKEY : fileKey;
	}

	@Deprecated
	public static FileSnapshot save(long modified) {
		final Instant read = Instant.now();
		return new FileSnapshot(read, Instant.ofEpochMilli(modified),
				UNKNOWN_SIZE, FALLBACK_TIMESTAMP_RESOLUTION, MISSING_FILEKEY);
	}

	public static FileSnapshot save(Instant modified) {
		final Instant read = Instant.now();
		return new FileSnapshot(read, modified, UNKNOWN_SIZE,
				FALLBACK_TIMESTAMP_RESOLUTION, MISSING_FILEKEY);
	}

	private final Instant lastModified;
	private volatile Instant lastRead;
	private boolean cannotBeRacilyClean;
	private final long size;
	private FileStoreAttributes fileStoreAttributeCache;
	private boolean useConfig;
	private final Object fileKey;
	private final File file;

	protected FileSnapshot(File file) {
		this(file, true);
	}

	protected FileSnapshot(File file, boolean useConfig) {
		this.file = file;
		this.lastRead = Instant.now();
		this.useConfig = useConfig;
		BasicFileAttributes fileAttributes;
		try {
			fileAttributes = FS.DETECTED.fileAttributes(file);
		} catch(NoSuchFileException e) {
			this.lastModified = Instant.EPOCH;
			this.size = 0L;
			this.fileKey = MISSING_FILEKEY;
			return;
		} catch(IOException e) {
			LOG.error(e.getMessage(), e);
			this.lastModified = Instant.EPOCH;
			this.size = 0L;
			this.fileKey = MISSING_FILEKEY;
			return;
		}
		this.lastModified = fileAttributes.lastModifiedTime().toInstant();
		this.size = fileAttributes.size();
		this.fileKey = getFileKey(fileAttributes);
		if(LOG.isDebugEnabled()) {
			LOG.debug("file={}, create new FileSnapshot: lastRead={}, lastModified={}, size={}, fileKey={}",
					file, dateFmt.format(lastRead), dateFmt.format(lastModified), size, fileKey.toString());
		}
	}

	private boolean sizeChanged;
	private boolean fileKeyChanged;
	private boolean lastModifiedChanged;
	private boolean wasRacyClean;
	private long delta;
	private long racyThreshold;

	private FileSnapshot(Instant read, Instant modified, long size,
						 @NonNull Duration fsTimestampResolution, @NonNull Object fileKey) {
		this.file = null;
		this.lastRead = read;
		this.lastModified = modified;
		this.fileStoreAttributeCache = new FileStoreAttributes(
				fsTimestampResolution);
		this.size = size;
		this.fileKey = fileKey;
	}

	@Deprecated
	public long lastModified() {
		return lastModified.toEpochMilli();
	}

	public Instant lastModifiedInstant() {
		return lastModified;
	}

	public long size() {
		return size;
	}

	public boolean isModified(File path) {
		Instant currLastModified;
		long currSize;
		Object currFileKey;
		try {
			BasicFileAttributes fileAttributes = FS.DETECTED.fileAttributes(path);
			currLastModified = fileAttributes.lastModifiedTime().toInstant();
			currSize = fileAttributes.size();
			currFileKey = getFileKey(fileAttributes);
		} catch(NoSuchFileException e) {
			currLastModified = Instant.EPOCH;
			currSize = 0L;
			currFileKey = MISSING_FILEKEY;
		} catch(IOException e) {
			LOG.error(e.getMessage(), e);
			currLastModified = Instant.EPOCH;
			currSize = 0L;
			currFileKey = MISSING_FILEKEY;
		}
		sizeChanged = isSizeChanged(currSize);
		if(sizeChanged) {
			return true;
		}
		fileKeyChanged = isFileKeyChanged(currFileKey);
		if(fileKeyChanged) {
			return true;
		}
		lastModifiedChanged = isModified(currLastModified);
		return lastModifiedChanged;
	}

	public void setClean(FileSnapshot other) {
		final Instant now = other.lastRead;
		if(!isRacyClean(now)) {
			cannotBeRacilyClean = true;
		}
		lastRead = now;
	}

	public void waitUntilNotRacy() throws InterruptedException {
		long timestampResolution = fileStoreAttributeCache()
				.getFsTimestampResolution().toNanos();
		while(isRacyClean(Instant.now())) {
			TimeUnit.NANOSECONDS.sleep(timestampResolution);
		}
	}

	public boolean equals(FileSnapshot other) {
		boolean sizeEq = size == UNKNOWN_SIZE || other.size == UNKNOWN_SIZE || size == other.size;
		return lastModified.equals(other.lastModified) && sizeEq
				&& Objects.equals(fileKey, other.fileKey);
	}

	@Override
	public boolean equals(Object obj) {
		if(this == obj) {
			return true;
		}
		if(obj == null) {
			return false;
		}
		if(!(obj instanceof FileSnapshot)) {
			return false;
		}
		FileSnapshot other = (FileSnapshot) obj;
		return equals(other);
	}

	@Override
	public int hashCode() {
		return Objects.hash(lastModified, size, fileKey);
	}

	boolean wasSizeChanged() {
		return sizeChanged;
	}

	boolean wasFileKeyChanged() {
		return fileKeyChanged;
	}

	boolean wasLastModifiedRacilyClean() {
		return wasRacyClean;
	}

	public long lastDelta() {
		return delta;
	}

	public long lastRacyThreshold() {
		return racyThreshold;
	}

	@Override
	public String toString() {
		if(this == DIRTY) {
			return "DIRTY";
		}
		if(this == MISSING_FILE) {
			return "MISSING_FILE";
		}
		return "FileSnapshot[modified: " + dateFmt.format(lastModified)
				+ ", read: " + dateFmt.format(lastRead) + ", size:" + size
				+ ", fileKey: " + fileKey + "]";
	}

	private boolean isRacyClean(Instant read) {
		racyThreshold = getEffectiveRacyThreshold();
		delta = Duration.between(lastModified, read).toNanos();
		wasRacyClean = delta <= racyThreshold;
		if(LOG.isDebugEnabled()) {
			LOG.debug(
					"file={}, isRacyClean={}, read={}, lastModified={}, delta={} ns, racy<={} ns",
					file, wasRacyClean, dateFmt.format(read),
					dateFmt.format(lastModified), delta,
					racyThreshold);
		}
		return wasRacyClean;
	}

	private long getEffectiveRacyThreshold() {
		long timestampResolution = fileStoreAttributeCache()
				.getFsTimestampResolution().toNanos();
		long minRacyInterval = fileStoreAttributeCache()
				.getMinimalRacyInterval().toNanos();
		long max = Math.max(timestampResolution, minRacyInterval);
		return max < 100_000_000L ? max * 5 / 2 : max * 5 / 4;
	}

	private boolean isModified(Instant currLastModified) {

		lastModifiedChanged = !lastModified.equals(currLastModified);
		if(lastModifiedChanged) {
			if(LOG.isDebugEnabled()) {
				LOG.debug(
						"file={}, lastModified changed from {} to {}",
						file, dateFmt.format(lastModified),
						dateFmt.format(currLastModified));
			}
			return true;
		}

		if(cannotBeRacilyClean) {
			LOG.debug("file={}, cannot be racily clean", file);
			return false;
		}
		if(!isRacyClean(lastRead)) {
			LOG.debug("file={}, is unmodified", file);
			return false;
		}

		LOG.debug("file={}, is racily clean", file);
		return true;
	}

	private boolean isFileKeyChanged(Object currFileKey) {
		boolean changed = currFileKey != MISSING_FILEKEY
				&& !currFileKey.equals(fileKey);
		if(changed) {
			LOG.debug("file={}, FileKey changed from {} to {}",
					file, fileKey, currFileKey);
		}
		return changed;
	}

	private boolean isSizeChanged(long currSize) {
		boolean changed = (currSize != UNKNOWN_SIZE) && (currSize != size);
		if(changed) {
			LOG.debug("file={}, size changed from {} to {} bytes",
					file, size, currSize);
		}
		return changed;
	}

	private FileStoreAttributes fileStoreAttributeCache() {
		if(fileStoreAttributeCache == null) {
			fileStoreAttributeCache = useConfig
					? FS.getFileStoreAttributes(file.toPath().getParent()) : FALLBACK_FILESTORE_ATTRIBUTES;
		}
		return fileStoreAttributeCache;
	}
}
