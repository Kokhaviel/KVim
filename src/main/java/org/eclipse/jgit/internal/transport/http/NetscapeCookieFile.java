/*
 * Copyright (C) 2018, Konrad Windszus <konrad_w@gmx.de> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.internal.transport.http;

import org.eclipse.jgit.annotations.*;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.internal.storage.file.*;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpCookie;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class NetscapeCookieFile {

	private static final String HTTP_ONLY_PREAMBLE = "#HttpOnly_";
	private static final String COLUMN_SEPARATOR = "\t";
	private static final String LINE_SEPARATOR = "\n";
	private static final int LOCK_ACQUIRE_MAX_RETRY_COUNT = 4;
	private static final int LOCK_ACQUIRE_RETRY_SLEEP = 500;

	private final Path path;
	private FileSnapshot snapshot;
	private byte[] hash;
	private final Instant createdAt;
	private Set<HttpCookie> cookies = null;
	private static final Logger LOG = LoggerFactory
			.getLogger(NetscapeCookieFile.class);

	public NetscapeCookieFile(Path path) {
		this(path, Instant.now());
	}

	NetscapeCookieFile(Path path, Instant createdAt) {
		this.path = path;
		this.snapshot = FileSnapshot.DIRTY;
		this.createdAt = createdAt;
	}

	public Path getPath() {
		return path;
	}

	public Set<HttpCookie> getCookies(boolean refresh) {
		if(cookies == null || refresh) {
			try {
				byte[] in = getFileContentIfModified();
				Set<HttpCookie> newCookies = parseCookieFile(in, createdAt);
				if(cookies != null) {
					cookies = mergeCookies(newCookies, cookies);
				} else {
					cookies = newCookies;
				}
				return cookies;
			} catch(IOException | IllegalArgumentException e) {
				LOG.warn(
						MessageFormat.format(
								JGitText.get().couldNotReadCookieFile, path),
						e);
				if(cookies == null) {
					cookies = new LinkedHashSet<>();
				}
			}
		}
		return cookies;

	}

	private static Set<HttpCookie> parseCookieFile(@NonNull byte[] input,
												   @NonNull Instant createdAt)
			throws IOException, IllegalArgumentException {

		String decoded = RawParseUtils.decode(StandardCharsets.US_ASCII, input);

		Set<HttpCookie> cookies = new LinkedHashSet<>();
		try(BufferedReader reader = new BufferedReader(
				new StringReader(decoded))) {
			String line;
			while((line = reader.readLine()) != null) {
				HttpCookie cookie = parseLine(line, createdAt);
				if(cookie != null) {
					cookies.add(cookie);
				}
			}
		}
		return cookies;
	}

	private static HttpCookie parseLine(@NonNull String line,
										@NonNull Instant createdAt) {
		if(line.isEmpty() || (line.startsWith("#")
				&& !line.startsWith(HTTP_ONLY_PREAMBLE))) {
			return null;
		}
		String[] cookieLineParts = line.split(COLUMN_SEPARATOR, 7);
		if(cookieLineParts.length < 7) {
			throw new IllegalArgumentException(MessageFormat.format(
					JGitText.get().couldNotFindSixTabsInLine, cookieLineParts.length, line));
		}
		String name = cookieLineParts[5];
		String value = cookieLineParts[6];
		HttpCookie cookie = new HttpCookie(name, value);

		String domain = cookieLineParts[0];
		if(domain.startsWith(HTTP_ONLY_PREAMBLE)) {
			cookie.setHttpOnly(true);
			domain = domain.substring(HTTP_ONLY_PREAMBLE.length());
		}

		if(domain.startsWith(".")) {
			domain = domain.substring(1);
		}
		cookie.setDomain(domain);
		cookie.setPath(cookieLineParts[2]);
		cookie.setSecure(Boolean.parseBoolean(cookieLineParts[3]));

		long expires = Long.parseLong(cookieLineParts[4]);
		if(cookieLineParts[4].length() == 13) {
			expires = TimeUnit.MILLISECONDS.toSeconds(expires);
		}
		long maxAge = expires - createdAt.getEpochSecond();
		if(maxAge <= 0) {
			return null;
		}
		cookie.setMaxAge(maxAge);
		return cookie;
	}

	private byte[] getFileContentIfModified() throws IOException {
		final int maxStaleRetries = 5;
		int retries = 0;
		File file = getPath().toFile();
		if(!file.exists()) {
			LOG.warn(MessageFormat.format(JGitText.get().missingCookieFile,
					file.getAbsolutePath()));
			return new byte[0];
		}
		while(true) {
			final FileSnapshot oldSnapshot = snapshot;
			final FileSnapshot newSnapshot = FileSnapshot.save(file);
			try {
				final byte[] in = IO.readFully(file);
				byte[] newHash = hash(in);
				if(Arrays.equals(hash, newHash)) {
					if(oldSnapshot.equals(newSnapshot)) {
						oldSnapshot.setClean(newSnapshot);
					} else {
						snapshot = newSnapshot;
					}
				} else {
					snapshot = newSnapshot;
					hash = newHash;
				}
				return in;
			} catch(FileNotFoundException e) {
				throw e;
			} catch(IOException e) {
				if(FileUtils.isStaleFileHandle(e)
						&& retries < maxStaleRetries) {
					if(LOG.isDebugEnabled()) {
						LOG.debug(MessageFormat.format(
								JGitText.get().configHandleIsStale, retries), e);
					}
					retries++;
					continue;
				}
				throw new IOException(MessageFormat
						.format(JGitText.get().cannotReadFile, getPath()), e);
			}
		}

	}

	private static byte[] hash(final byte[] in) {
		return Constants.newMessageDigest().digest(in);
	}

	public void write(URL url) throws IOException, InterruptedException {
		try {
			byte[] cookieFileContent = getFileContentIfModified();
			LOG.debug("Reading the underlying cookie file '{}' "
					+ "as it has been modified since "
					+ "the last access", path);
			Set<HttpCookie> cookiesFromFile = NetscapeCookieFile
					.parseCookieFile(cookieFileContent, createdAt);
			this.cookies = mergeCookies(cookiesFromFile, cookies);
		} catch(FileNotFoundException ignored) {
		}

		ByteArrayOutputStream output = new ByteArrayOutputStream();
		try(Writer writer = new OutputStreamWriter(output,
				StandardCharsets.US_ASCII)) {
			write(writer, cookies, url, createdAt);
		}
		LockFile lockFile = new LockFile(path.toFile());
		for(int retryCount = 0; retryCount < LOCK_ACQUIRE_MAX_RETRY_COUNT; retryCount++) {
			if(lockFile.lock()) {
				try {
					lockFile.setNeedSnapshot(true);
					lockFile.write(output.toByteArray());
					if(!lockFile.commit()) {
						throw new IOException(MessageFormat.format(
								JGitText.get().cannotCommitWriteTo, path));
					}
				} finally {
					lockFile.unlock();
				}
				return;
			}
			Thread.sleep(LOCK_ACQUIRE_RETRY_SLEEP);
		}
		throw new IOException(
				MessageFormat.format(JGitText.get().cannotLock, lockFile));
	}

	static void write(@NonNull Writer writer,
					  @NonNull Collection<HttpCookie> cookies, @NonNull URL url,
					  @NonNull Instant createdAt) throws IOException {
		for(HttpCookie cookie : cookies) {
			writeCookie(writer, cookie, url, createdAt);
		}
	}

	private static void writeCookie(@NonNull Writer writer,
									@NonNull HttpCookie cookie, @NonNull URL url,
									@NonNull Instant createdAt) throws IOException {
		if(cookie.getMaxAge() <= 0) {
			return;
		}
		String domain = "";
		if(cookie.isHttpOnly()) {
			domain = HTTP_ONLY_PREAMBLE;
		}
		if(cookie.getDomain() != null) {
			domain += cookie.getDomain();
		} else {
			domain += url.getHost();
		}
		writer.write(domain);
		writer.write(COLUMN_SEPARATOR);
		writer.write("TRUE");
		writer.write(COLUMN_SEPARATOR);
		String path = cookie.getPath();
		if(path == null) {
			path = url.getPath();
		}
		writer.write(path);
		writer.write(COLUMN_SEPARATOR);
		writer.write(Boolean.toString(cookie.getSecure()).toUpperCase());
		writer.write(COLUMN_SEPARATOR);
		final String expirationDate;
		expirationDate = String
				.valueOf(createdAt.getEpochSecond() + cookie.getMaxAge());
		writer.write(expirationDate);
		writer.write(COLUMN_SEPARATOR);
		writer.write(cookie.getName());
		writer.write(COLUMN_SEPARATOR);
		writer.write(cookie.getValue());
		writer.write(LINE_SEPARATOR);
	}

	static Set<HttpCookie> mergeCookies(Set<HttpCookie> cookies1,
										@Nullable Set<HttpCookie> cookies2) {
		Set<HttpCookie> mergedCookies = new LinkedHashSet<>(cookies1);
		if(cookies2 != null) {
			mergedCookies.addAll(cookies2);
		}
		return mergedCookies;
	}
}
