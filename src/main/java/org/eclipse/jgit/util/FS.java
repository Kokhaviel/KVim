/*
 * Copyright (C) 2008, 2020 Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.util;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.Instant.EPOCH;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.AccessControlException;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.text.MessageFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.errors.CommandFailedException;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.errors.LockFailedException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.internal.storage.file.FileSnapshot;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.treewalk.FileTreeIterator.FileEntry;
import org.eclipse.jgit.treewalk.FileTreeIterator.FileModeStrategy;
import org.eclipse.jgit.treewalk.WorkingTreeIterator.Entry;
import org.eclipse.jgit.util.ProcessResult.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class FS {
	private static final Logger LOG = LoggerFactory.getLogger(FS.class);

	protected static final Entry[] NO_ENTRIES = {};

	private static final Pattern VERSION = Pattern.compile("\\s(\\d+)\\.(\\d+)\\.(\\d+)");

	private volatile Boolean supportSymlinks;

	public static class FSFactory {

		protected FSFactory() {
		}

		public FS detect(Boolean cygwinUsed) {
			if(SystemReader.getInstance().isWindows()) {
				if(cygwinUsed == null) {
					cygwinUsed = FS_Win32_Cygwin.isCygwin();
				}
				if(cygwinUsed) {
					return new FS_Win32_Cygwin();
				}
				return new FS_Win32();
			}
			return new FS_POSIX();
		}
	}

	public static class ExecutionResult {
		private final TemporaryBuffer stdout;

		private final TemporaryBuffer stderr;

		private final int rc;

		public ExecutionResult(TemporaryBuffer stdout, TemporaryBuffer stderr,
							   int rc) {
			this.stdout = stdout;
			this.stderr = stderr;
			this.rc = rc;
		}

		public TemporaryBuffer getStdout() {
			return stdout;
		}

		public TemporaryBuffer getStderr() {
			return stderr;
		}

		public int getRc() {
			return rc;
		}
	}

	public static final class FileStoreAttributes {

		private static final Duration UNDEFINED_DURATION = Duration
				.ofNanos(Long.MAX_VALUE);

		public static final Duration FALLBACK_TIMESTAMP_RESOLUTION = Duration
				.ofMillis(2000);

		public static final FileStoreAttributes FALLBACK_FILESTORE_ATTRIBUTES = new FileStoreAttributes(
				FALLBACK_TIMESTAMP_RESOLUTION);

		private static final long ONE_MICROSECOND = TimeUnit.MICROSECONDS
				.toNanos(1);

		private static final long ONE_MILLISECOND = TimeUnit.MILLISECONDS
				.toNanos(1);

		private static final long ONE_SECOND = TimeUnit.SECONDS.toNanos(1);

		private static final long MINIMUM_RESOLUTION_NANOS = ONE_MICROSECOND;

		private static final String JAVA_VERSION_PREFIX = System
				.getProperty("java.vendor") + '|'
				+ System.getProperty("java.version") + '|';

		private static final Duration FALLBACK_MIN_RACY_INTERVAL = Duration
				.ofMillis(10);

		private static final Map<FileStore, FileStoreAttributes> attributeCache = new ConcurrentHashMap<>();

		private static final SimpleLruCache<Path, FileStoreAttributes> attrCacheByPath = new SimpleLruCache<>(
				100, 0.2f);

		private static final AtomicBoolean background = new AtomicBoolean();

		private static final Map<FileStore, Lock> locks = new ConcurrentHashMap<>();

		private static final AtomicInteger threadNumber = new AtomicInteger(1);

		private static final Executor FUTURE_RUNNER = new ThreadPoolExecutor(0,
				5, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
				runnable -> {
					Thread t = new Thread(runnable,
							"JGit-FileStoreAttributeReader-"
									+ threadNumber.getAndIncrement());
					t.setDaemon(true);
					return t;
				});

		private static final Executor SAVE_RUNNER = new ThreadPoolExecutor(0, 1,
				1L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
				runnable -> {
					Thread t = new Thread(runnable,
							"JGit-FileStoreAttributeWriter-"
									+ threadNumber.getAndIncrement());
					t.setDaemon(false);
					return t;
				});

		public static FileStoreAttributes get(Path path) {
			try {
				path = path.toAbsolutePath();
				Path dir = Files.isDirectory(path) ? path : path.getParent();
				if(dir == null) {
					return FALLBACK_FILESTORE_ATTRIBUTES;
				}
				FileStoreAttributes cached = attrCacheByPath.get(dir);
				if(cached != null) {
					return cached;
				}
				FileStoreAttributes attrs = getFileStoreAttributes(dir);
				if(attrs == null) {
					return FALLBACK_FILESTORE_ATTRIBUTES;
				}
				attrCacheByPath.put(dir, attrs);
				return attrs;
			} catch(SecurityException e) {
				return FALLBACK_FILESTORE_ATTRIBUTES;
			}
		}

		private static FileStoreAttributes getFileStoreAttributes(Path dir) {
			FileStore s;
			try {
				if(Files.exists(dir)) {
					s = Files.getFileStore(dir);
					FileStoreAttributes c = attributeCache.get(s);
					if(c != null) {
						return c;
					}
					if(!Files.isWritable(dir)) {
						LOG.debug(
								"{}: cannot measure timestamp resolution in read-only directory {}",
								Thread.currentThread(), dir);
						return FALLBACK_FILESTORE_ATTRIBUTES;
					}
				} else {
					LOG.debug(
							"{}: cannot measure timestamp resolution of unborn directory {}",
							Thread.currentThread(), dir);
					return FALLBACK_FILESTORE_ATTRIBUTES;
				}

				CompletableFuture<Optional<FileStoreAttributes>> f = CompletableFuture
						.supplyAsync(() -> {
							Lock lock = locks.computeIfAbsent(s,
									l -> new ReentrantLock());
							if(!lock.tryLock()) {
								LOG.debug(
										"{}: couldn't get lock to measure timestamp resolution in {}",
										Thread.currentThread(), dir);
								return Optional.empty();
							}
							Optional<FileStoreAttributes> attributes;
							try {
								FileStoreAttributes c = attributeCache.get(s);
								if(c != null) {
									return Optional.of(c);
								}
								attributes = readFromConfig(s);
								if(attributes.isPresent()) {
									attributeCache.put(s, attributes.get());
									return attributes;
								}

								Optional<Duration> resolution = measureFsTimestampResolution(
										s, dir);
								if(resolution.isPresent()) {
									c = new FileStoreAttributes(
											resolution.get());
									attributeCache.put(s, c);
									if(c.fsTimestampResolution
											.toNanos() < 100_000_000L) {
										c.minimalRacyInterval = measureMinimalRacyInterval(
												dir);
									}
									if(LOG.isDebugEnabled()) {
										LOG.debug(c.toString());
									}
									FileStoreAttributes newAttrs = c;
									SAVE_RUNNER.execute(
											() -> saveToConfig(s, newAttrs));
								}
								attributes = Optional.of(c);
							} finally {
								lock.unlock();
								locks.remove(s);
							}
							return attributes;
						}, FUTURE_RUNNER);
				f = f.exceptionally(e -> {
					LOG.error(e.getLocalizedMessage(), e);
					return Optional.empty();
				});
				boolean runInBackground = background.get();
				Optional<FileStoreAttributes> d = runInBackground ? f.get(
						100, TimeUnit.MILLISECONDS) : f.get();
				if(d.isPresent()) {
					return d.get();
				} else if(runInBackground) {
					return null;
				}
			} catch(IOException | InterruptedException
					| ExecutionException | CancellationException e) {
				LOG.error(e.getMessage(), e);
			} catch(TimeoutException | SecurityException ignored) {
			}
			LOG.debug("{}: use fallback timestamp resolution for directory {}",
					Thread.currentThread(), dir);
			return FALLBACK_FILESTORE_ATTRIBUTES;
		}

		private static Duration measureMinimalRacyInterval(Path dir) {
			LOG.debug("{}: start measure minimal racy interval in {}",
					Thread.currentThread(), dir);
			int n = 0;
			int failures = 0;
			long racyNanos = 0;
			ArrayList<Long> deltas = new ArrayList<>();
			Path probe = dir.resolve(".probe-" + UUID.randomUUID());
			Instant end = Instant.now().plusSeconds(3);
			try {
				Files.createFile(probe);
				do {
					n++;
					write(probe, "a");
					FileSnapshot snapshot = FileSnapshot.save(probe.toFile());
					read(probe);
					write(probe, "b");
					if(!snapshot.isModified(probe.toFile())) {
						deltas.add(snapshot.lastDelta());
						racyNanos = snapshot.lastRacyThreshold();
						failures++;
					}
				} while(Instant.now().compareTo(end) < 0);
			} catch(IOException e) {
				LOG.error(e.getMessage(), e);
				return FALLBACK_MIN_RACY_INTERVAL;
			} finally {
				deleteProbe(probe);
			}
			if(failures > 0) {
				Stats stats = new Stats();
				for(Long d : deltas) {
					stats.add(d);
				}
				LOG.debug(
						"delta [ns] since modification FileSnapshot failed to detect\n"
								+ "count, failures, racy limit [ns], delta min [ns],"
								+ " delta max [ns], delta avg [ns],"
								+ " delta stddev [ns]\n"
								+ "{}, {}, {}, {}, {}, {}, {}",
						n, failures, racyNanos, stats.min(), stats.max(),
						stats.avg(), stats.stddev());
				return Duration
						.ofNanos(Double.valueOf(stats.max()).longValue());
			}
			LOG.debug("{}: no failures when measuring minimal racy interval",
					Thread.currentThread());
			return Duration.ZERO;
		}

		private static void write(Path p, String body) throws IOException {
			Path parent = p.getParent();
			if(parent != null) {
				FileUtils.mkdirs(parent.toFile(), true);
			}
			try(Writer w = new OutputStreamWriter(Files.newOutputStream(p),
					UTF_8)) {
				w.write(body);
			}
		}

		private static String read(Path p) throws IOException {
			byte[] body = IO.readFully(p.toFile());
			return new String(body, 0, body.length, UTF_8);
		}

		private static Optional<Duration> measureFsTimestampResolution(
				FileStore s, Path dir) {
			if(LOG.isDebugEnabled()) {
				LOG.debug("{}: start measure timestamp resolution {} in {}",
						Thread.currentThread(), s, dir);
			}
			Path probe = dir.resolve(".probe-" + UUID.randomUUID());
			try {
				Files.createFile(probe);
				Duration fsResolution = getFsResolution(s, dir, probe);
				Duration clockResolution = measureClockResolution();
				fsResolution = fsResolution.plus(clockResolution);
				if(LOG.isDebugEnabled()) {
					LOG.debug(
							"{}: end measure timestamp resolution {} in {}; got {}",
							Thread.currentThread(), s, dir, fsResolution);
				}
				return Optional.of(fsResolution);
			} catch(SecurityException | AccessDeniedException e) {
				LOG.warn(e.getLocalizedMessage(), e);
			} catch(IOException e) {
				LOG.error(e.getLocalizedMessage(), e);
			} finally {
				deleteProbe(probe);
			}
			return Optional.empty();
		}

		private static Duration getFsResolution(FileStore s, Path dir,
												Path probe) throws IOException {
			File probeFile = probe.toFile();
			FileTime t1 = Files.getLastModifiedTime(probe);
			Instant t1i = t1.toInstant();
			FileTime t2;
			Duration last = FALLBACK_TIMESTAMP_RESOLUTION;
			long minScale = MINIMUM_RESOLUTION_NANOS;
			long scale = ONE_SECOND;
			long high = TimeUnit.MILLISECONDS.toSeconds(last.toMillis());
			long low = 0;
			long[] tries = {ONE_MICROSECOND, ONE_MILLISECOND};
			for(long interval : tries) {
				if(interval >= ONE_MILLISECOND) {
					probeFile.setLastModified(
							t1i.plusNanos(interval).toEpochMilli());
				} else {
					Files.setLastModifiedTime(probe,
							FileTime.from(t1i.plusNanos(interval)));
				}
				t2 = Files.getLastModifiedTime(probe);
				if(t2.compareTo(t1) > 0) {
					Duration diff = Duration.between(t1i, t2.toInstant());
					if(!diff.isZero() && !diff.isNegative()
							&& diff.compareTo(last) < 0) {
						scale = interval;
						high = 1;
						last = diff;
						break;
					}
				} else {
					minScale = Math.max(minScale, interval);
				}
			}
			while(high > low) {
				long mid = (high + low) / 2;
				if(mid == 0) {
					long newScale = scale / 10;
					if(newScale < minScale) {
						break;
					}
					high *= (double) scale / newScale;
					low *= (double) scale / newScale;
					scale = newScale;
					mid = (high + low) / 2;
				}
				long delta = mid * scale;
				if(scale >= ONE_MILLISECOND) {
					probeFile.setLastModified(
							t1i.plusNanos(delta).toEpochMilli());
				} else {
					Files.setLastModifiedTime(probe,
							FileTime.from(t1i.plusNanos(delta)));
				}
				t2 = Files.getLastModifiedTime(probe);
				int cmp = t2.compareTo(t1);
				if(cmp > 0) {
					high = mid;
					Duration diff = Duration.between(t1i, t2.toInstant());
					if(diff.isZero() || diff.isNegative()) {
						LOG.warn(JGitText.get().logInconsistentFiletimeDiff,
								Thread.currentThread(), s, dir, t2, t1, diff,
								last);
						break;
					} else if(diff.compareTo(last) > 0) {
						LOG.warn(JGitText.get().logLargerFiletimeDiff,
								Thread.currentThread(), s, dir, diff, last);
						break;
					}
					last = diff;
				} else if(cmp < 0) {
					LOG.warn(JGitText.get().logSmallerFiletime,
							Thread.currentThread(), s, dir, t2, t1, last);
					break;
				} else {
					low = mid + 1;
				}
			}
			return last;
		}

		private static Duration measureClockResolution() {
			Duration clockResolution = Duration.ZERO;
			for(int i = 0; i < 10; i++) {
				Instant t1 = Instant.now();
				Instant t2 = t1;
				while(t2.compareTo(t1) <= 0) {
					t2 = Instant.now();
				}
				Duration r = Duration.between(t1, t2);
				if(r.compareTo(clockResolution) > 0) {
					clockResolution = r;
				}
			}
			return clockResolution;
		}

		private static void deleteProbe(Path probe) {
			try {
				FileUtils.delete(probe.toFile(),
						FileUtils.SKIP_MISSING | FileUtils.RETRY);
			} catch(IOException e) {
				LOG.error(e.getMessage(), e);
			}
		}

		private static Optional<FileStoreAttributes> readFromConfig(
				FileStore s) {
			StoredConfig userConfig;
			try {
				userConfig = SystemReader.getInstance().getUserConfig();
			} catch(IOException | ConfigInvalidException e) {
				LOG.error(JGitText.get().readFileStoreAttributesFailed, e);
				return Optional.empty();
			}
			String key = getConfigKey(s);
			Duration resolution = Duration.ofNanos(userConfig.getTimeUnit(
					ConfigConstants.CONFIG_FILESYSTEM_SECTION, key,
					ConfigConstants.CONFIG_KEY_TIMESTAMP_RESOLUTION,
					UNDEFINED_DURATION.toNanos(), TimeUnit.NANOSECONDS));
			if(UNDEFINED_DURATION.equals(resolution)) {
				return Optional.empty();
			}
			Duration minRacyThreshold = Duration.ofNanos(userConfig.getTimeUnit(
					ConfigConstants.CONFIG_FILESYSTEM_SECTION, key,
					ConfigConstants.CONFIG_KEY_MIN_RACY_THRESHOLD,
					UNDEFINED_DURATION.toNanos(), TimeUnit.NANOSECONDS));
			FileStoreAttributes c = new FileStoreAttributes(resolution);
			if(!UNDEFINED_DURATION.equals(minRacyThreshold)) {
				c.minimalRacyInterval = minRacyThreshold;
			}
			return Optional.of(c);
		}

		private static void saveToConfig(FileStore s,
										 FileStoreAttributes c) {
			StoredConfig jgitConfig;
			try {
				jgitConfig = SystemReader.getInstance().getJGitConfig();
			} catch(IOException | ConfigInvalidException e) {
				LOG.error(JGitText.get().saveFileStoreAttributesFailed, e);
				return;
			}
			long resolution = c.getFsTimestampResolution().toNanos();
			TimeUnit resolutionUnit = getUnit(resolution);
			long resolutionValue = resolutionUnit.convert(resolution,
					TimeUnit.NANOSECONDS);

			long minRacyThreshold = c.getMinimalRacyInterval().toNanos();
			TimeUnit minRacyThresholdUnit = getUnit(minRacyThreshold);
			long minRacyThresholdValue = minRacyThresholdUnit
					.convert(minRacyThreshold, TimeUnit.NANOSECONDS);

			final int max_retries = 5;
			int retries = 0;
			boolean succeeded = false;
			String key = getConfigKey(s);
			while(!succeeded && retries < max_retries) {
				try {
					jgitConfig.setString(
							ConfigConstants.CONFIG_FILESYSTEM_SECTION, key,
							ConfigConstants.CONFIG_KEY_TIMESTAMP_RESOLUTION,
							String.format("%d %s",
									resolutionValue,
									resolutionUnit.name().toLowerCase()));
					jgitConfig.setString(
							ConfigConstants.CONFIG_FILESYSTEM_SECTION, key,
							ConfigConstants.CONFIG_KEY_MIN_RACY_THRESHOLD,
							String.format("%d %s",
									minRacyThresholdValue,
									minRacyThresholdUnit.name().toLowerCase()));
					jgitConfig.save();
					succeeded = true;
				} catch(LockFailedException e) {
					try {
						retries++;
						if(retries < max_retries) {
							Thread.sleep(100);
							LOG.debug("locking {} failed, retries {}/{}",
									jgitConfig, retries,
									max_retries);
						} else {
							LOG.warn(MessageFormat.format(
									JGitText.get().lockFailedRetry, jgitConfig,
									retries));
						}
					} catch(InterruptedException e1) {
						Thread.currentThread().interrupt();
						break;
					}
				} catch(IOException e) {
					LOG.error(MessageFormat.format(
							JGitText.get().cannotSaveConfig, jgitConfig), e);
					break;
				}
			}
		}

		private static String getConfigKey(FileStore s) {
			String storeKey;
			if(SystemReader.getInstance().isWindows()) {
				Object attribute = null;
				try {
					attribute = s.getAttribute("volume:vsn");
				} catch(IOException ignored) {
				}
				if(attribute instanceof Integer) {
					storeKey = attribute.toString();
				} else {
					storeKey = s.name();
				}
			} else {
				storeKey = s.name();
			}
			return JAVA_VERSION_PREFIX + storeKey;
		}

		private static TimeUnit getUnit(long nanos) {
			TimeUnit unit;
			if(nanos < 200_000L) {
				unit = TimeUnit.NANOSECONDS;
			} else if(nanos < 200_000_000L) {
				unit = TimeUnit.MICROSECONDS;
			} else {
				unit = TimeUnit.MILLISECONDS;
			}
			return unit;
		}

		private final @NonNull Duration fsTimestampResolution;

		private Duration minimalRacyInterval;

		public Duration getMinimalRacyInterval() {
			return minimalRacyInterval;
		}

		@NonNull
		public Duration getFsTimestampResolution() {
			return fsTimestampResolution;
		}

		public FileStoreAttributes(
				@NonNull Duration fsTimestampResolution) {
			this.fsTimestampResolution = fsTimestampResolution;
			this.minimalRacyInterval = Duration.ZERO;
		}

		@SuppressWarnings({"nls", "boxing"})
		@Override
		public String toString() {
			return String.format(
					"FileStoreAttributes[fsTimestampResolution=%,d µs, "
							+ "minimalRacyInterval=%,d µs]",
					fsTimestampResolution.toNanos() / 1000,
					minimalRacyInterval.toNanos() / 1000);
		}

	}

	public static final FS DETECTED = detect();

	private static volatile FSFactory factory;

	public static FS detect() {
		return detect(null);
	}

	public static FS detect(Boolean cygwinUsed) {
		if(factory == null) {
			factory = new FS.FSFactory();
		}
		return factory.detect(cygwinUsed);
	}

	public static FileStoreAttributes getFileStoreAttributes(
			@NonNull Path dir) {
		return FileStoreAttributes.get(dir);
	}

	private volatile Holder<File> userHome;

	private volatile Holder<File> gitSystemConfig;

	protected FS() {
	}

	public abstract boolean supportsExecute();

	public boolean supportsAtomicCreateNewFile() {
		return true;
	}

	public boolean supportsSymlinks() {
		if(supportSymlinks == null) {
			detectSymlinkSupport();
		}
		return Boolean.TRUE.equals(supportSymlinks);
	}

	private void detectSymlinkSupport() {
		File tempFile = null;
		try {
			tempFile = File.createTempFile("tempsymlinktarget", "");
			File linkName = new File(tempFile.getParentFile(), "tempsymlink");
			createSymLink(linkName, tempFile.getPath());
			supportSymlinks = Boolean.TRUE;
			linkName.delete();
		} catch(IOException | UnsupportedOperationException | SecurityException
				| InternalError e) {
			supportSymlinks = Boolean.FALSE;
		} finally {
			if(tempFile != null) {
				try {
					FileUtils.delete(tempFile);
				} catch(IOException e) {
					LOG.error(JGitText.get().cannotDeleteFile, tempFile);
				}
			}
		}
	}

	public abstract boolean isCaseSensitive();

	public abstract boolean canExecute(File f);

	public abstract boolean setExecute(File f, boolean canExec);

	public Instant lastModifiedInstant(File f) {
		return FileUtils.lastModifiedInstant(f.toPath());
	}

	public void setLastModified(Path p, Instant time) throws IOException {
		FileUtils.setLastModified(p, time);
	}

	public long length(File path) throws IOException {
		return FileUtils.getLength(path);
	}

	public void delete(File f) throws IOException {
		FileUtils.delete(f);
	}

	public File resolve(File dir, String name) {
		File abspn = new File(name);
		if(abspn.isAbsolute())
			return abspn;
		return new File(dir, name);
	}

	public File userHome() {
		Holder<File> p = userHome;
		if(p == null) {
			p = new Holder<>(safeUserHomeImpl());
			userHome = p;
		}
		return p.value;
	}

	private File safeUserHomeImpl() {
		File home;
		try {
			home = userHomeImpl();
			if(home != null) {
				home.toPath();
				return home;
			}
		} catch(RuntimeException e) {
			LOG.error(JGitText.get().exceptionWhileFindingUserHome, e);
		}
		home = defaultUserHomeImpl();
		if(home != null) {
			try {
				home.toPath();
				return home;
			} catch(InvalidPathException e) {
				LOG.error(MessageFormat
						.format(JGitText.get().invalidHomeDirectory, home), e);
			}
		}
		return null;
	}

	public abstract boolean retryFailedLockFileCommit();

	public BasicFileAttributes fileAttributes(File file) throws IOException {
		return FileUtils.fileAttributes(file);
	}

	protected File userHomeImpl() {
		return defaultUserHomeImpl();
	}

	private File defaultUserHomeImpl() {
		String home = AccessController.doPrivileged(
				(PrivilegedAction<String>) () -> System.getProperty("user.home")
		);
		if(home == null || home.length() == 0)
			return null;
		return new File(home).getAbsoluteFile();
	}

	protected static File searchPath(String path, String... lookFor) {
		if(path == null) {
			return null;
		}

		for(String p : path.split(File.pathSeparator)) {
			for(String command : lookFor) {
				File file = new File(p, command);
				try {
					if(file.isFile() && file.canExecute()) {
						return file.getAbsoluteFile();
					}
				} catch(SecurityException e) {
					LOG.warn(MessageFormat.format(
							JGitText.get().skipNotAccessiblePath,
							file.getPath()));
				}
			}
		}
		return null;
	}

	@Nullable
	protected static String readPipe(File dir, String[] command,
									 String encoding) throws CommandFailedException {
		return readPipe(dir, command, encoding, null);
	}

	@Nullable
	protected static String readPipe(File dir, String[] command,
									 String encoding, Map<String, String> env)
			throws CommandFailedException {
		boolean debug = LOG.isDebugEnabled();
		try {
			if(debug) {
				LOG.debug("readpipe " + Arrays.asList(command) + ","
						+ dir);
			}
			ProcessBuilder pb = new ProcessBuilder(command);
			pb.directory(dir);
			if(env != null) {
				pb.environment().putAll(env);
			}
			Process p;
			try {
				p = pb.start();
			} catch(IOException e) {
				throw new CommandFailedException(e.getMessage(), e);
			}
			p.getOutputStream().close();
			GobblerThread gobbler = new GobblerThread(p, command, dir);
			gobbler.start();
			String r;
			try(BufferedReader lineRead = new BufferedReader(
					new InputStreamReader(p.getInputStream(), encoding))) {
				r = lineRead.readLine();
				if(debug) {
					LOG.debug("readpipe may return '" + r + "'");
					LOG.debug("remaining output:\n");
					String l;
					while((l = lineRead.readLine()) != null) {
						LOG.debug(l);
					}
				}
			}

			for(; ; ) {
				try {
					int rc = p.waitFor();
					gobbler.join();
					if(rc == 0 && !gobbler.fail.get()) {
						return r;
					}
					if(debug) {
						LOG.debug("readpipe rc=" + rc);
					}
					throw new CommandFailedException(
							gobbler.errorMessage.get(),
							gobbler.exception.get());
				} catch(InterruptedException ignored) {
				}
			}
		} catch(IOException e) {
			LOG.error("Caught exception in FS.readPipe()", e);
		} catch(AccessControlException e) {
			LOG.warn(MessageFormat.format(
					JGitText.get().readPipeIsNotAllowedRequiredPermission,
					command, dir, e.getPermission()));
		} catch(SecurityException e) {
			LOG.warn(MessageFormat.format(JGitText.get().readPipeIsNotAllowed,
					command, dir));
		}
		if(debug) {
			LOG.debug("readpipe returns null");
		}
		return null;
	}

	private static class GobblerThread extends Thread {

		private static final int PROCESS_EXIT_TIMEOUT = 5;

		private final Process p;
		private final String desc;
		private final String dir;
		final AtomicBoolean fail = new AtomicBoolean();
		final AtomicReference<String> errorMessage = new AtomicReference<>();
		final AtomicReference<Throwable> exception = new AtomicReference<>();

		GobblerThread(Process p, String[] command, File dir) {
			this.p = p;
			this.desc = Arrays.toString(command);
			this.dir = Objects.toString(dir);
		}

		@Override
		public void run() {
			StringBuilder err = new StringBuilder();
			try(InputStream is = p.getErrorStream()) {
				int ch;
				while((ch = is.read()) != -1) {
					err.append((char) ch);
				}
			} catch(IOException e) {
				if(waitForProcessCompletion(e) && p.exitValue() != 0) {
					setError(e, e.getMessage(), p.exitValue());
					fail.set(true);
				}
			} finally {
				if(waitForProcessCompletion(null) && err.length() > 0) {
					setError(null, err.toString(), p.exitValue());
					if(p.exitValue() != 0) {
						fail.set(true);
					}
				}
			}
		}

		@SuppressWarnings("boxing")
		private boolean waitForProcessCompletion(IOException originalError) {
			try {
				if(!p.waitFor(PROCESS_EXIT_TIMEOUT, TimeUnit.SECONDS)) {
					setError(originalError, MessageFormat.format(
							JGitText.get().commandClosedStderrButDidntExit,
							desc, PROCESS_EXIT_TIMEOUT), -1);
					fail.set(true);
					return false;
				}
			} catch(InterruptedException e) {
				setError(originalError, MessageFormat.format(
						JGitText.get().threadInterruptedWhileRunning, desc), -1);
				fail.set(true);
				return false;
			}
			return true;
		}

		private void setError(IOException e, String message, int exitCode) {
			exception.set(e);
			errorMessage.set(MessageFormat.format(
					JGitText.get().exceptionCaughtDuringExecutionOfCommand,
					desc, dir, exitCode, message));
		}
	}

	protected abstract File discoverGitExe();

	protected File discoverGitSystemConfig() {
		File gitExe = discoverGitExe();
		if(gitExe == null) {
			return null;
		}

		String v;
		try {
			v = readPipe(gitExe.getParentFile(),
					new String[] {gitExe.getPath(), "--version"},
					SystemReader.getInstance().getDefaultCharset().name());
		} catch(CommandFailedException e) {
			LOG.warn(e.getMessage());
			return null;
		}
		if(StringUtils.isEmptyOrNull(v) || v.startsWith("jgit")) {
			return null;
		}

		if(parseVersion(v) < makeVersion(2, 8, 0)) {
			Map<String, String> env = new HashMap<>();
			env.put("GIT_EDITOR", "echo");

			String w;
			try {
				w = readPipe(gitExe.getParentFile(),
						new String[] {gitExe.getPath(), "config", "--system",
								"--edit"},
						SystemReader.getInstance().getDefaultCharset().name(),
						env);
			} catch(CommandFailedException e) {
				LOG.warn(e.getMessage());
				return null;
			}
			if(StringUtils.isEmptyOrNull(w)) {
				return null;
			}

			return new File(w);
		}
		String w;
		try {
			w = readPipe(gitExe.getParentFile(),
					new String[] {gitExe.getPath(), "config", "--system",
							"--show-origin", "--list", "-z"},
					SystemReader.getInstance().getDefaultCharset().name());
		} catch(CommandFailedException e) {
			if(LOG.isDebugEnabled()) {
				LOG.debug(e.getMessage());
			}
			return null;
		}
		if(w == null) {
			return null;
		}
		int nul = w.indexOf(0);
		if(nul <= 0) {
			return null;
		}
		w = w.substring(0, nul);
		int colon = w.indexOf(':');
		if(colon < 0) {
			return null;
		}
		w = w.substring(colon + 1);
		return w.isEmpty() ? null : new File(w);
	}

	private long parseVersion(String version) {
		Matcher m = VERSION.matcher(version);
		if(m.find()) {
			try {
				return makeVersion(
						Integer.parseInt(m.group(1)),
						Integer.parseInt(m.group(2)),
						Integer.parseInt(m.group(3)));
			} catch(NumberFormatException ignored) {
			}
		}
		return -1;
	}

	private long makeVersion(int major, int minor, int patch) {
		return ((major * 10_000L) + minor) * 10_000L + patch;
	}

	public File getGitSystemConfig() {
		if(gitSystemConfig == null) {
			gitSystemConfig = new Holder<>(discoverGitSystemConfig());
		}
		return gitSystemConfig.value;
	}

	public String readSymLink(File path) throws IOException {
		return FileUtils.readSymLink(path);
	}

	public boolean exists(File path) {
		return FileUtils.exists(path);
	}

	public boolean isDirectory(File path) {
		return FileUtils.isDirectory(path);
	}

	public boolean isFile(File path) {
		return FileUtils.isFile(path);
	}

	public void setHidden(File path, boolean hidden) throws IOException {
		FileUtils.setHidden(path, hidden);
	}

	public void createSymLink(File path, String target) throws IOException {
		FileUtils.createSymLink(path, target);
	}

	@Deprecated
	public boolean createNewFile(File path) throws IOException {
		return path.createNewFile();
	}

	public static class LockToken implements Closeable {
		private final boolean isCreated;

		private final Optional<Path> link;

		LockToken(boolean isCreated, Optional<Path> link) {
			this.isCreated = isCreated;
			this.link = link;
		}

		public boolean isCreated() {
			return isCreated;
		}

		@Override
		public void close() {
			if(!link.isPresent()) {
				return;
			}
			Path p = link.get();
			if(!Files.exists(p)) {
				return;
			}
			try {
				Files.delete(p);
			} catch(IOException e) {
				LOG.error(MessageFormat
						.format(JGitText.get().closeLockTokenFailed, this), e);
			}
		}

		@Override
		public String toString() {
			return "LockToken [lockCreated=" + isCreated +
					", link="
					+ (link.map(path -> path.getFileName() + "]").orElse("<null>]"));
		}
	}

	public LockToken createNewFileAtomic(File path) throws IOException {
		return new LockToken(path.createNewFile(), Optional.empty());
	}

	public String relativize(String base, String other) {
		return FileUtils.relativizePath(base, other, File.separator, this.isCaseSensitive());
	}

	public Entry[] list(File directory, FileModeStrategy fileModeStrategy) {
		File[] all = directory.listFiles();
		if(all == null) {
			return NO_ENTRIES;
		}
		Entry[] result = new Entry[all.length];
		for(int i = 0; i < result.length; i++) {
			result[i] = new FileEntry(all[i], this, fileModeStrategy);
		}
		return result;
	}

	public ProcessResult runHookIfPresent(Repository repository,
										  String hookName, String[] args, OutputStream outRedirect,
										  OutputStream errRedirect, String stdinArgs)
			throws JGitInternalException {
		return new ProcessResult(Status.NOT_SUPPORTED);
	}

	protected ProcessResult internalRunHookIfPresent(Repository repository,
													 String hookName, String[] args, OutputStream outRedirect,
													 OutputStream errRedirect, String stdinArgs)
			throws JGitInternalException {
		File hookFile = findHook(repository, hookName);
		if(hookFile == null || hookName == null) {
			return new ProcessResult(Status.NOT_PRESENT);
		}

		File runDirectory = getRunDirectory(repository, hookName);
		if(runDirectory == null) {
			return new ProcessResult(Status.NOT_PRESENT);
		}
		String cmd = hookFile.getAbsolutePath();
		ProcessBuilder hookProcess = runInShell(shellQuote(cmd), args);
		hookProcess.directory(runDirectory.getAbsoluteFile());
		Map<String, String> environment = hookProcess.environment();
		environment.put(Constants.GIT_DIR_KEY,
				repository.getDirectory().getAbsolutePath());
		if(!repository.isBare()) {
			environment.put(Constants.GIT_WORK_TREE_KEY,
					repository.getWorkTree().getAbsolutePath());
		}
		try {
			return new ProcessResult(runProcess(hookProcess, outRedirect,
					errRedirect, stdinArgs), Status.OK);
		} catch(IOException e) {
			throw new JGitInternalException(MessageFormat.format(
					JGitText.get().exceptionCaughtDuringExecutionOfHook,
					hookName), e);
		} catch(InterruptedException e) {
			throw new JGitInternalException(MessageFormat.format(
					JGitText.get().exceptionHookExecutionInterrupted,
					hookName), e);
		}
	}

	String shellQuote(String cmd) {
		return cmd;
	}

	public File findHook(Repository repository, String hookName) {
		if(hookName == null) {
			return null;
		}
		File hookDir = getHooksDirectory(repository);
		if(hookDir == null) {
			return null;
		}
		File hookFile = new File(hookDir, hookName);
		if(hookFile.isAbsolute()) {
			if(!hookFile.exists() || (FS.DETECTED.supportsExecute()
					&& !FS.DETECTED.canExecute(hookFile))) {
				return null;
			}
		} else {
			try {
				File runDirectory = getRunDirectory(repository, hookName);
				if(runDirectory == null) {
					return null;
				}
				Path hookPath = runDirectory.getAbsoluteFile().toPath()
						.resolve(hookFile.toPath());
				FS fs = repository.getFS();
				if(fs == null) {
					fs = FS.DETECTED;
				}
				if(!Files.exists(hookPath) || (fs.supportsExecute()
						&& !fs.canExecute(hookPath.toFile()))) {
					return null;
				}
				hookFile = hookPath.toFile();
			} catch(InvalidPathException e) {
				LOG.warn(MessageFormat.format(JGitText.get().invalidHooksPath,
						hookFile));
				return null;
			}
		}
		return hookFile;
	}

	private File getRunDirectory(Repository repository,
								 @NonNull String hookName) {
		if(repository.isBare()) {
			return repository.getDirectory();
		}
		switch(hookName) {
			case "pre-receive":
			case "update":
			case "post-receive":
			case "post-update":
			case "push-to-checkout":
				return repository.getDirectory();
			default:
				return repository.getWorkTree();
		}
	}

	private File getHooksDirectory(Repository repository) {
		Config config = repository.getConfig();
		String hooksDir = config.getString(ConfigConstants.CONFIG_CORE_SECTION,
				null, ConfigConstants.CONFIG_KEY_HOOKS_PATH);
		if(hooksDir != null) {
			return new File(hooksDir);
		}
		File dir = repository.getDirectory();
		return dir == null ? null : new File(dir, Constants.HOOKS);
	}

	public int runProcess(ProcessBuilder processBuilder,
						  OutputStream outRedirect, OutputStream errRedirect, String stdinArgs)
			throws IOException, InterruptedException {
		InputStream in = (stdinArgs == null) ? null : new ByteArrayInputStream(
				stdinArgs.getBytes(UTF_8));
		return runProcess(processBuilder, outRedirect, errRedirect, in);
	}

	public int runProcess(ProcessBuilder processBuilder,
						  OutputStream outRedirect, OutputStream errRedirect,
						  InputStream inRedirect) throws IOException,
			InterruptedException {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		Process process = null;
		// We'll record the first I/O exception that occurs, but keep on trying
		// to dispose of our open streams and file handles
		IOException ioException = null;
		try {
			process = processBuilder.start();
			executor.execute(
					new StreamGobbler(process.getErrorStream(), errRedirect));
			executor.execute(
					new StreamGobbler(process.getInputStream(), outRedirect));
			try(OutputStream outputStream = process.getOutputStream()) {
				if(inRedirect != null) {
					new StreamGobbler(inRedirect, outputStream).copy();
				}
			}
			return process.waitFor();
		} catch(IOException e) {
			ioException = e;
		} finally {
			shutdownAndAwaitTermination(executor);
			if(process != null) {
				try {
					process.waitFor();
				} catch(InterruptedException e) {
					Thread.interrupted();
				}
				if(inRedirect != null) {
					inRedirect.close();
				}
				try {
					process.getErrorStream().close();
				} catch(IOException e) {
					ioException = ioException != null ? ioException : e;
				}
				try {
					process.getInputStream().close();
				} catch(IOException e) {
					ioException = ioException != null ? ioException : e;
				}
				try {
					process.getOutputStream().close();
				} catch(IOException e) {
					ioException = ioException != null ? ioException : e;
				}
				process.destroy();
			}
		}
		throw ioException;
	}

	private static boolean shutdownAndAwaitTermination(ExecutorService pool) {
		boolean hasShutdown = true;
		pool.shutdown();
		try {
			if(!pool.awaitTermination(60, TimeUnit.SECONDS)) {
				pool.shutdownNow();
				if(!pool.awaitTermination(60, TimeUnit.SECONDS))
					hasShutdown = false;
			}
		} catch(InterruptedException ie) {
			pool.shutdownNow();
			Thread.currentThread().interrupt();
			hasShutdown = false;
		}
		return hasShutdown;
	}

	public abstract ProcessBuilder runInShell(String cmd, String[] args);

	public ExecutionResult execute(ProcessBuilder pb, InputStream in)
			throws IOException, InterruptedException {
		try(TemporaryBuffer stdout = new TemporaryBuffer.LocalFile(null);
			TemporaryBuffer stderr = new TemporaryBuffer.Heap(1024,
					1024 * 1024)) {
			int rc = runProcess(pb, stdout, stderr, in);
			return new ExecutionResult(stdout, stderr, rc);
		}
	}

	private static class Holder<V> {
		final V value;

		Holder(V value) {
			this.value = value;
		}
	}

	public static class Attributes {

		public boolean isDirectory() {
			return isDirectory;
		}

		public boolean isExecutable() {
			return isExecutable;
		}

		public boolean isSymbolicLink() {
			return isSymbolicLink;
		}

		@Deprecated
		public long getLastModifiedTime() {
			return lastModifiedInstant.toEpochMilli();
		}

		public Instant getLastModifiedInstant() {
			return lastModifiedInstant;
		}

		private final boolean isDirectory;

		private final boolean isSymbolicLink;

		private final Instant lastModifiedInstant;

		private final boolean isExecutable;

		private final File file;

		protected long length;

		final FS fs;

		Attributes(FS fs, File file, boolean isDirectory,
				   boolean isExecutable, boolean isSymbolicLink,
				   boolean isRegularFile, long creationTime,
				   Instant lastModifiedInstant, long length) {
			this.fs = fs;
			this.file = file;
			this.isDirectory = isDirectory;
			this.isExecutable = isExecutable;
			this.isSymbolicLink = isSymbolicLink;
			this.lastModifiedInstant = lastModifiedInstant;
			this.length = length;
		}

		public Attributes(File path, FS fs) {
			this(fs, path, false, false, false, false, 0L, EPOCH, 0L);
		}

		public long getLength() {
			if(length == -1)
				return length = file.length();
			return length;
		}

		public String getName() {
			return file.getName();
		}

		public File getFile() {
			return file;
		}

	}

	public Attributes getAttributes(File path) {
		boolean isDirectory = isDirectory(path);
		boolean isFile = !isDirectory && path.isFile();
		assert path.exists() == isDirectory || isFile;
		boolean exists = isDirectory || isFile;
		boolean canExecute = exists && !isDirectory && canExecute(path);
		boolean isSymlink = false;
		Instant lastModified = exists ? lastModifiedInstant(path) : EPOCH;
		long createTime = 0L;
		return new Attributes(this, path, isDirectory, canExecute,
				isSymlink, isFile, createTime, lastModified, -1);
	}

	public File normalize(File file) {
		return file;
	}

	public String normalize(String name) {
		return name;
	}

	private static class StreamGobbler implements Runnable {
		private final InputStream in;

		private final OutputStream out;

		public StreamGobbler(InputStream stream, OutputStream output) {
			this.in = stream;
			this.out = output;
		}

		@Override
		public void run() {
			try {
				copy();
			} catch(IOException ignored) {
			}
		}

		void copy() throws IOException {
			boolean writeFailure = false;
			byte[] buffer = new byte[4096];
			int readBytes;
			while((readBytes = in.read(buffer)) != -1) {
				if(!writeFailure && out != null) {
					try {
						out.write(buffer, 0, readBytes);
						out.flush();
					} catch(IOException e) {
						writeFailure = true;
					}
				}
			}
		}
	}
}
