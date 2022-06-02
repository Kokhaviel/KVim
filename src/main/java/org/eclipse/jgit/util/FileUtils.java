/*
 * Copyright (C) 2010, Google Inc.
 * Copyright (C) 2010, Matthias Sohn <matthias.sohn@sap.com>
 * Copyright (C) 2010, Jens Baumgart <jens.baumgart@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.util;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.util.FS.Attributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.text.MessageFormat;
import java.text.Normalizer;
import java.text.Normalizer.Form;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;

public class FileUtils {
	private static final Logger LOG = LoggerFactory.getLogger(FileUtils.class);

	private static final Random RNG = new Random();
	public static final int NONE = 0;
	public static final int RECURSIVE = 1;
	public static final int RETRY = 2;
	public static final int SKIP_MISSING = 4;
	public static final int IGNORE_ERRORS = 8;
	public static final int EMPTY_DIRECTORIES_ONLY = 16;

	public static Path toPath(File f) throws IOException {
		try {
			return f.toPath();
		} catch(InvalidPathException ex) {
			throw new IOException(ex);
		}
	}

	public static void delete(File f) throws IOException {
		delete(f, NONE);
	}

	public static void delete(File f, int options) throws IOException {
		FS fs = FS.DETECTED;
		if((options & SKIP_MISSING) != 0 && !fs.exists(f))
			return;

		if((options & RECURSIVE) != 0 && fs.isDirectory(f)) {
			final File[] items = f.listFiles();
			if(items != null) {
				List<File> files = new ArrayList<>();
				List<File> dirs = new ArrayList<>();
				for(File c : items)
					if(c.isFile())
						files.add(c);
					else
						dirs.add(c);
				for(File file : files)
					delete(file, options);
				for(File d : dirs)
					delete(d, options);
			}
		}

		boolean delete = false;
		if((options & EMPTY_DIRECTORIES_ONLY) != 0) {
			if(f.isDirectory()) {
				delete = true;
			} else if((options & IGNORE_ERRORS) == 0) {
				throw new IOException(MessageFormat.format(
						JGitText.get().deleteFileFailed, f.getAbsolutePath()));
			}
		} else {
			delete = true;
		}

		if(delete) {
			IOException t = null;
			Path p = f.toPath();
			boolean tryAgain;
			do {
				tryAgain = false;
				try {
					Files.delete(p);
					return;
				} catch(NoSuchFileException | FileNotFoundException e) {
					handleDeleteException(f, e, options,
							SKIP_MISSING | IGNORE_ERRORS);
					return;
				} catch(DirectoryNotEmptyException e) {
					handleDeleteException(f, e, options, IGNORE_ERRORS);
					return;
				} catch(IOException e) {
					if(!f.canWrite()) {
						tryAgain = f.setWritable(true);
					}
					if(!tryAgain) {
						t = e;
					}
				}
			} while(tryAgain);

			if((options & RETRY) != 0) {
				for(int i = 1; i < 10; i++) {
					try {
						Thread.sleep(100);
					} catch(InterruptedException ignored) {
					}
					try {
						Files.deleteIfExists(p);
						return;
					} catch(IOException e) {
						t = e;
					}
				}
			}
			handleDeleteException(f, t, options, IGNORE_ERRORS);
		}
	}

	private static void handleDeleteException(File f, IOException e,
											  int allOptions, int checkOptions) throws IOException {
		if(e != null && (allOptions & checkOptions) == 0) {
			throw new IOException(MessageFormat.format(
					JGitText.get().deleteFileFailed, f.getAbsolutePath()), e);
		}
	}

	public static void rename(File src, File dst)
			throws IOException {
		rename(src, dst, StandardCopyOption.REPLACE_EXISTING);
	}

	public static void rename(final File src, final File dst,
							  CopyOption... options)
			throws IOException {
		int attempts = FS.DETECTED.retryFailedLockFileCommit() ? 10 : 1;
		while(--attempts >= 0) {
			try {
				Files.move(toPath(src), toPath(dst), options);
				return;
			} catch(AtomicMoveNotSupportedException e) {
				throw e;
			} catch(IOException e) {
				try {
					if(!dst.delete()) {
						delete(dst, EMPTY_DIRECTORIES_ONLY | RECURSIVE);
					}
					Files.move(toPath(src), toPath(dst), options);
					return;
				} catch(IOException ignored) {
				}
			}
			try {
				Thread.sleep(100);
			} catch(InterruptedException e) {
				throw new IOException(
						MessageFormat.format(JGitText.get().renameFileFailed,
								src.getAbsolutePath(), dst.getAbsolutePath()),
						e);
			}
		}
		throw new IOException(
				MessageFormat.format(JGitText.get().renameFileFailed,
						src.getAbsolutePath(), dst.getAbsolutePath()));
	}

	public static void mkdir(File d)
			throws IOException {
		mkdir(d, false);
	}

	public static void mkdir(File d, boolean skipExisting)
			throws IOException {
		if(!d.mkdir()) {
			if(skipExisting && d.isDirectory())
				return;
			throw new IOException(MessageFormat.format(
					JGitText.get().mkDirFailed, d.getAbsolutePath()));
		}
	}

	public static void mkdirs(File d) throws IOException {
		mkdirs(d, false);
	}

	public static void mkdirs(File d, boolean skipExisting)
			throws IOException {
		if(!d.mkdirs()) {
			if(skipExisting && d.isDirectory())
				return;
			throw new IOException(MessageFormat.format(
					JGitText.get().mkDirsFailed, d.getAbsolutePath()));
		}
	}

	public static Path createSymLink(File path, String target)
			throws IOException {
		Path nioPath = toPath(path);
		if(Files.exists(nioPath, LinkOption.NOFOLLOW_LINKS)) {
			BasicFileAttributes attrs = Files.readAttributes(nioPath,
					BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			if(attrs.isRegularFile() || attrs.isSymbolicLink()) {
				delete(path);
			} else {
				delete(path, EMPTY_DIRECTORIES_ONLY | RECURSIVE);
			}
		}
		if(SystemReader.getInstance().isWindows()) {
			target = target.replace('/', '\\');
		}
		Path nioTarget = toPath(new File(target));
		return Files.createSymbolicLink(nioPath, nioTarget);
	}

	public static String readSymLink(File path) throws IOException {
		Path nioPath = toPath(path);
		Path target = Files.readSymbolicLink(nioPath);
		String targetString = target.toString();
		if(SystemReader.getInstance().isWindows()) {
			targetString = targetString.replace('\\', '/');
		} else if(SystemReader.getInstance().isMacOS()) {
			targetString = Normalizer.normalize(targetString, Form.NFC);
		}
		return targetString;
	}

	public static File createTempDir(String prefix, String suffix, File dir)
			throws IOException {
		final int RETRIES = 1;
		for(int i = 0; i < RETRIES; i++) {
			File tmp = File.createTempFile(prefix, suffix, dir);
			if(!tmp.delete())
				continue;
			if(!tmp.mkdir())
				continue;
			return tmp;
		}
		throw new IOException(JGitText.get().cannotCreateTempDir);
	}

	public static String relativizeGitPath(String base, String other) {
		return relativizePath(base, other, "/", false);
	}

	public static String relativizePath(String base, String other, String dirSeparator, boolean caseSensitive) {
		if(base.equals(other))
			return "";

		final String[] baseSegments = base.split(Pattern.quote(dirSeparator));
		final String[] otherSegments = other.split(Pattern
				.quote(dirSeparator));

		int commonPrefix = 0;
		while(commonPrefix < baseSegments.length
				&& commonPrefix < otherSegments.length) {
			if(caseSensitive
					&& baseSegments[commonPrefix]
					.equals(otherSegments[commonPrefix]))
				commonPrefix++;
			else if(!caseSensitive
					&& baseSegments[commonPrefix]
					.equalsIgnoreCase(otherSegments[commonPrefix]))
				commonPrefix++;
			else
				break;
		}

		final StringBuilder builder = new StringBuilder();
		for(int i = commonPrefix; i < baseSegments.length; i++)
			builder.append("..").append(dirSeparator);
		for(int i = commonPrefix; i < otherSegments.length; i++) {
			builder.append(otherSegments[i]);
			if(i < otherSegments.length - 1)
				builder.append(dirSeparator);
		}
		return builder.toString();
	}

	public static boolean isStaleFileHandle(IOException ioe) {
		String msg = ioe.getMessage();
		return msg != null
				&& msg.toLowerCase(Locale.ROOT)
				.matches("stale .*file .*handle");
	}

	public static boolean isStaleFileHandleInCausalChain(Throwable throwable) {
		while(throwable != null) {
			if(throwable instanceof IOException
					&& isStaleFileHandle((IOException) throwable)) {
				return true;
			}
			throwable = throwable.getCause();
		}
		return false;
	}

	@FunctionalInterface
	public interface IOFunction<A, B> {

		B apply(A t) throws Exception;
	}

	private static void backOff(long delay, IOException cause)
			throws IOException {
		try {
			Thread.sleep(delay);
		} catch(InterruptedException e) {
			IOException interruption = new InterruptedIOException();
			interruption.initCause(e);
			interruption.addSuppressed(cause);
			Thread.currentThread().interrupt();
			throw interruption;
		}
	}

	public static <T> T readWithRetries(File file,
										IOFunction<File, ? extends T> reader)
			throws Exception {
		int maxStaleRetries = 5;
		int retries = 0;
		long backoff = 50;
		while(true) {
			try {
				try {
					return reader.apply(file);
				} catch(IOException e) {
					if(FileUtils.isStaleFileHandleInCausalChain(e)
							&& retries < maxStaleRetries) {
						if(LOG.isDebugEnabled()) {
							LOG.debug(MessageFormat.format(
									JGitText.get().packedRefsHandleIsStale,
									retries), e);
						}
						retries++;
						continue;
					}
					throw e;
				}
			} catch(FileNotFoundException noFile) {
				if(!file.isFile()) {
					return null;
				}
				if(backoff > 1000) {
					throw noFile;
				}
				backOff(backoff, noFile);
				backoff *= 2;
			}
		}
	}

	static Instant lastModifiedInstant(Path path) {
		try {
			return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS)
					.toInstant();
		} catch(NoSuchFileException e) {
			LOG.debug(
					"Cannot read lastModifiedInstant since path {} does not exist",
					path);
			return Instant.EPOCH;
		} catch(IOException e) {
			LOG.error(MessageFormat
					.format(JGitText.get().readLastModifiedFailed, path), e);
			return Instant.ofEpochMilli(path.toFile().lastModified());
		}
	}

	static BasicFileAttributes fileAttributes(File file) throws IOException {
		return Files.readAttributes(file.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
	}

	static void setLastModified(Path path, Instant time)
			throws IOException {
		Files.setLastModifiedTime(path, FileTime.from(time));
	}

	static boolean exists(File file) {
		return Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS);
	}

	public static void setHidden(File file, boolean hidden) throws IOException {
		Files.setAttribute(toPath(file), "dos:hidden", hidden,
				LinkOption.NOFOLLOW_LINKS);
	}

	public static long getLength(File file) throws IOException {
		Path nioPath = toPath(file);
		if(Files.isSymbolicLink(nioPath))
			return Files.readSymbolicLink(nioPath).toString()
					.getBytes(UTF_8).length;
		return Files.size(nioPath);
	}

	static boolean isDirectory(File file) {
		return Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS);
	}

	static boolean isFile(File file) {
		return Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS);
	}

	public static boolean hasFiles(Path dir) throws IOException {
		try(Stream<Path> stream = Files.list(dir)) {
			return stream.findAny().isPresent();
		}
	}

	public static boolean canExecute(File file) {
		if(!isFile(file)) {
			return false;
		}
		return Files.isExecutable(file.toPath());
	}

	static Attributes getFileAttributesBasic(FS fs, File file) {
		try {
			Path nioPath = toPath(file);
			BasicFileAttributes readAttributes = nioPath
					.getFileSystem()
					.provider()
					.getFileAttributeView(nioPath,
							BasicFileAttributeView.class,
							LinkOption.NOFOLLOW_LINKS).readAttributes();
			return new Attributes(fs, file,
					readAttributes.isDirectory(),
					fs.supportsExecute() && file.canExecute(),
					readAttributes.isSymbolicLink(),
					readAttributes.isRegularFile(), //
					readAttributes.creationTime().toMillis(), //
					readAttributes.lastModifiedTime().toInstant(),
					readAttributes.isSymbolicLink() ? Constants
							.encode(readSymLink(file)).length
							: readAttributes.size());
		} catch(IOException e) {
			return new Attributes(file, fs);
		}
	}

	public static Attributes getFileAttributesPosix(FS fs, File file) {
		try {
			Path nioPath = toPath(file);
			PosixFileAttributes readAttributes = nioPath
					.getFileSystem()
					.provider()
					.getFileAttributeView(nioPath,
							PosixFileAttributeView.class,
							LinkOption.NOFOLLOW_LINKS).readAttributes();
			return new Attributes(
					fs,
					file,
					readAttributes.isDirectory(),
					readAttributes.permissions().contains(
							PosixFilePermission.OWNER_EXECUTE),
					readAttributes.isSymbolicLink(),
					readAttributes.isRegularFile(),
					readAttributes.creationTime().toMillis(),
					readAttributes.lastModifiedTime().toInstant(),
					readAttributes.size());
		} catch(IOException e) {
			return new Attributes(file, fs);
		}
	}

	public static File normalize(File file) {
		if(SystemReader.getInstance().isMacOS()) {
			String normalized = Normalizer.normalize(file.getPath(),
					Normalizer.Form.NFC);
			return new File(normalized);
		}
		return file;
	}

	public static String normalize(String name) {
		if(SystemReader.getInstance().isMacOS()) {
			if(name == null)
				return null;
			return Normalizer.normalize(name, Normalizer.Form.NFC);
		}
		return name;
	}

	public static long delay(long last, long min, long max) {
		long r = Math.max(0, last * 3 - min);
		if(r > 0) {
			int c = (int) Math.min(r + 1, Integer.MAX_VALUE);
			r = RNG.nextInt(c);
		}
		return Math.max(Math.min(min + r, max), min);
	}
}
