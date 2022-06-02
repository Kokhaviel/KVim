/*
 * Copyright (C) 2009, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.lib;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.internal.WorkQueue;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.IO;
import org.eclipse.jgit.util.RawParseUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RepositoryCache {
	private static final Logger LOG = LoggerFactory
			.getLogger(RepositoryCache.class);

	private static final RepositoryCache cache = new RepositoryCache();

	public static Repository open(Key location) throws IOException {
		return open(location, true);
	}

	public static Repository open(Key location, boolean mustExist)
			throws IOException {
		return cache.openRepository(location, mustExist);
	}

	public static void close(@NonNull Repository db) {
		if(db.getDirectory() != null) {
			FileKey key = FileKey.exact(db.getDirectory(), db.getFS());
			cache.unregisterAndCloseRepository(key);
		}
	}

	static boolean isCached(@NonNull Repository repo) {
		File gitDir = repo.getDirectory();
		if(gitDir == null) {
			return false;
		}
		FileKey key = new FileKey(gitDir, repo.getFS());
		return cache.cacheMap.get(key) == repo;
	}

	public static void clear() {
		cache.clearAll();
	}

	private final Map<Key, Repository> cacheMap;
	private final Lock[] openLocks;
	private ScheduledFuture<?> cleanupTask;
	private volatile long expireAfter;
	private final Object schedulerLock = new Lock();

	private RepositoryCache() {
		cacheMap = new ConcurrentHashMap<>();
		openLocks = new Lock[4];
		for(int i = 0; i < openLocks.length; i++) {
			openLocks[i] = new Lock();
		}
		configureEviction(new RepositoryCacheConfig());
	}

	private void configureEviction(
			RepositoryCacheConfig repositoryCacheConfig) {
		expireAfter = repositoryCacheConfig.getExpireAfter();
		ScheduledThreadPoolExecutor scheduler = WorkQueue.getExecutor();
		synchronized(schedulerLock) {
			if(cleanupTask != null) {
				cleanupTask.cancel(false);
			}
			long delay = repositoryCacheConfig.getCleanupDelay();
			if(delay == RepositoryCacheConfig.NO_CLEANUP) {
				return;
			}
			cleanupTask = scheduler.scheduleWithFixedDelay(() -> {
				try {
					cache.clearAllExpired();
				} catch(Throwable e) {
					LOG.error(e.getMessage(), e);
				}
			}, delay, delay, TimeUnit.MILLISECONDS);
		}
	}

	private Repository openRepository(final Key location,
									  final boolean mustExist) throws IOException {
		Repository db = cacheMap.get(location);
		if(db == null) {
			synchronized(lockFor(location)) {
				db = cacheMap.get(location);
				if(db == null) {
					db = location.open(mustExist);
					cacheMap.put(location, db);
				} else {
					db.incrementOpen();
				}
			}
		} else {
			db.incrementOpen();
		}
		return db;
	}

	private Repository unregisterRepository(Key location) {
		return cacheMap.remove(location);
	}

	private boolean isExpired(Repository db) {
		return db != null && db.useCnt.get() <= 0
				&& (System.currentTimeMillis() - db.closedAt.get() > expireAfter);
	}

	private void unregisterAndCloseRepository(Key location) {
		synchronized(lockFor(location)) {
			Repository oldDb = unregisterRepository(location);
			if(oldDb != null) {
				oldDb.doClose();
			}
		}
	}

	private void clearAllExpired() {
		for(Repository db : cacheMap.values()) {
			if(isExpired(db)) {
				RepositoryCache.close(db);
			}
		}
	}

	private void clearAll() {
		for(Key k : cacheMap.keySet()) {
			unregisterAndCloseRepository(k);
		}
	}

	private Lock lockFor(Key location) {
		return openLocks[(location.hashCode() >>> 1) % openLocks.length];
	}

	private static class Lock {
	}

	public interface Key {

		Repository open(boolean mustExist) throws IOException;
	}

	public static class FileKey implements Key {

		public static FileKey exact(File directory, FS fs) {
			return new FileKey(directory, fs);
		}

		public static FileKey lenient(File directory, FS fs) {
			final File gitdir = resolve(directory, fs);
			return new FileKey(gitdir != null ? gitdir : directory, fs);
		}

		private final File path;
		private final FS fs;

		protected FileKey(File directory, FS fs) {
			path = canonical(directory);
			this.fs = fs;
		}

		private static File canonical(File path) {
			try {
				return path.getCanonicalFile();
			} catch(IOException e) {
				return path.getAbsoluteFile();
			}
		}

		public final File getFile() {
			return path;
		}

		@Override
		public Repository open(boolean mustExist) throws IOException {
			if(mustExist && !isGitRepository(path, fs))
				throw new RepositoryNotFoundException(path);
			return new FileRepository(path);
		}

		@Override
		public int hashCode() {
			return path.hashCode();
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof FileKey && path.equals(((FileKey) o).path);
		}

		@Override
		public String toString() {
			return path.toString();
		}

		public static boolean isGitRepository(File dir, FS fs) {
			return fs.resolve(dir, Constants.OBJECTS).exists()
					&& fs.resolve(dir, "refs").exists()
					&& (fs.resolve(dir, Constants.REFTABLE).exists()
					|| isValidHead(new File(dir, Constants.HEAD)));
		}

		private static boolean isValidHead(File head) {
			final String ref = readFirstLine(head);
			return ref != null
					&& (ref.startsWith("ref: refs/") || ObjectId.isId(ref));
		}

		private static String readFirstLine(File head) {
			try {
				final byte[] buf = IO.readFully(head, 4096);
				int n = buf.length;
				if(n == 0)
					return null;
				if(buf[n - 1] == '\n')
					n--;
				return RawParseUtils.decode(buf, 0, n);
			} catch(IOException e) {
				return null;
			}
		}

		public static File resolve(File directory, FS fs) {
			if(isGitRepository(directory, fs))
				return directory;
			if(isGitRepository(new File(directory, Constants.DOT_GIT), fs))
				return new File(directory, Constants.DOT_GIT);

			final String name = directory.getName();
			final File parent = directory.getParentFile();
			if(isGitRepository(new File(parent, name + Constants.DOT_GIT_EXT), fs))
				return new File(parent, name + Constants.DOT_GIT_EXT);
			return null;
		}
	}
}
