/*
 * Copyright (C) 2009, Google Inc.
 * Copyright (C) 2009, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2009, Yann Simon <yann.simon.fr@gmail.com>
 * Copyright (C) 2012, Daniel Megert <daniel_megert@ch.ibm.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.util;

import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectChecker;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicReference;

public abstract class SystemReader {

	private static final Logger LOG = LoggerFactory.getLogger(SystemReader.class);

	private static final SystemReader DEFAULT;
	private static volatile Boolean isMacOS;
	private static volatile Boolean isWindows;

	static {
		SystemReader r = new Default();
		r.init();
		DEFAULT = r;
	}

	private static class Default extends SystemReader {
		private volatile String hostname;

		@Override
		public String getenv(String variable) {
			return System.getenv(variable);
		}

		@Override
		public String getProperty(String key) {
			return System.getProperty(key);
		}

		@Override
		public FileBasedConfig openSystemConfig(Config parent, FS fs) {
			if(StringUtils.isEmptyOrNull(getenv(Constants.GIT_CONFIG_NOSYSTEM_KEY))) {
				File configFile = fs.getGitSystemConfig();
				if(configFile != null) {
					return new FileBasedConfig(parent, configFile, fs);
				}
			}
			return new FileBasedConfig(parent, null, fs) {
				@Override
				public void load() {
				}

				@Override
				public boolean isOutdated() {
					return false;
				}
			};
		}

		@Override
		public FileBasedConfig openUserConfig(Config parent, FS fs) {
			return new FileBasedConfig(parent, new File(fs.userHome(), ".gitconfig"),
					fs);
		}

		private Path getXDGConfigHome(FS fs) {
			String configHomePath = getenv(Constants.XDG_CONFIG_HOME);
			if(StringUtils.isEmptyOrNull(configHomePath)) {
				configHomePath = new File(fs.userHome(), ".config")
						.getAbsolutePath();
			}
			try {
				return Paths.get(configHomePath);
			} catch(InvalidPathException e) {
				LOG.error(JGitText.get().logXDGConfigHomeInvalid,
						configHomePath, e);
			}
			return null;
		}

		@Override
		public FileBasedConfig openJGitConfig(Config parent, FS fs) {
			Path xdgPath = getXDGConfigHome(fs);
			if(xdgPath != null) {
				Path configPath = xdgPath.resolve("jgit")
						.resolve(Constants.CONFIG);
				return new FileBasedConfig(parent, configPath.toFile(), fs);
			}
			return new FileBasedConfig(parent,
					new File(fs.userHome(), ".jgitconfig"), fs);
		}

		@Override
		public String getHostname() {
			if(hostname == null) {
				try {
					InetAddress localMachine = InetAddress.getLocalHost();
					hostname = localMachine.getCanonicalHostName();
				} catch(UnknownHostException e) {
					hostname = "localhost";
				}
				assert hostname != null;
			}
			return hostname;
		}

		@Override
		public long getCurrentTime() {
			return System.currentTimeMillis();
		}

		@Override
		public int getTimezone(long when) {
			return getTimeZone().getOffset(when) / (60 * 1000);
		}
	}

	private static final SystemReader INSTANCE = DEFAULT;

	public static SystemReader getInstance() {
		return INSTANCE;
	}

	private ObjectChecker platformChecker;
	private final AtomicReference<FileBasedConfig> systemConfig = new AtomicReference<>();
	private final AtomicReference<FileBasedConfig> userConfig = new AtomicReference<>();
	private final AtomicReference<FileBasedConfig> jgitConfig = new AtomicReference<>();

	private volatile Charset defaultCharset;

	private void init() {
		if(platformChecker == null)
			setPlatformChecker();
	}

	protected final void setPlatformChecker() {
		platformChecker = new ObjectChecker()
				.setSafeForWindows(isWindows())
				.setSafeForMacOS(isMacOS());
	}

	public abstract String getHostname();

	public abstract String getenv(String variable);

	public abstract String getProperty(String key);

	public abstract FileBasedConfig openUserConfig(Config parent, FS fs);

	public abstract FileBasedConfig openSystemConfig(Config parent, FS fs);

	public abstract FileBasedConfig openJGitConfig(Config parent, FS fs);

	public StoredConfig getUserConfig()
			throws ConfigInvalidException, IOException {
		FileBasedConfig c = userConfig.get();
		if(c == null) {
			userConfig.compareAndSet(null,
					openUserConfig(getSystemConfig(), FS.DETECTED));
			c = userConfig.get();
		}
		updateAll(c);
		return c;
	}

	public StoredConfig getJGitConfig()
			throws ConfigInvalidException, IOException {
		FileBasedConfig c = jgitConfig.get();
		if(c == null) {
			jgitConfig.compareAndSet(null,
					openJGitConfig(null, FS.DETECTED));
			c = jgitConfig.get();
		}
		updateAll(c);
		return c;
	}

	public StoredConfig getSystemConfig()
			throws ConfigInvalidException, IOException {
		FileBasedConfig c = systemConfig.get();
		if(c == null) {
			systemConfig.compareAndSet(null,
					openSystemConfig(getJGitConfig(), FS.DETECTED));
			c = systemConfig.get();
		}
		updateAll(c);
		return c;
	}

	private void updateAll(Config config)
			throws ConfigInvalidException, IOException {
		if(config == null) {
			return;
		}
		updateAll(config.getBaseConfig());
		if(config instanceof FileBasedConfig) {
			FileBasedConfig cfg = (FileBasedConfig) config;
			if(cfg.isOutdated()) {
				LOG.debug("loading config {}", cfg);
				cfg.load();
			}
		}
	}

	public abstract long getCurrentTime();

	public abstract int getTimezone(long when);

	public TimeZone getTimeZone() {
		return TimeZone.getDefault();
	}

	public Locale getLocale() {
		return Locale.getDefault();
	}

	public Charset getDefaultCharset() {
		Charset result = defaultCharset;
		if(result == null) {
			String encoding = getProperty("native.encoding");
			try {
				if(!StringUtils.isEmptyOrNull(encoding)) {
					result = Charset.forName(encoding);
				}
			} catch(IllegalCharsetNameException
					| UnsupportedCharsetException e) {
				LOG.error(JGitText.get().logInvalidDefaultCharset, encoding);
			}
			if(result == null) {
				result = Charset.defaultCharset();
			}
			defaultCharset = result;
		}
		return result;
	}

	public SimpleDateFormat getSimpleDateFormat(String pattern) {
		return new SimpleDateFormat(pattern);
	}

	public SimpleDateFormat getSimpleDateFormat(String pattern, Locale locale) {
		return new SimpleDateFormat(pattern, locale);
	}

	public DateFormat getDateTimeInstance(int dateStyle, int timeStyle) {
		return DateFormat.getDateTimeInstance(dateStyle, timeStyle);
	}

	public boolean isWindows() {
		if(isWindows == null) {
			String osDotName = getOsName();
			isWindows = osDotName.startsWith("Windows");
		}
		return isWindows;
	}

	public boolean isMacOS() {
		if(isMacOS == null) {
			String osDotName = getOsName();
			isMacOS = "Mac OS X".equals(osDotName) || "Darwin".equals(osDotName);
		}
		return isMacOS;
	}

	private String getOsName() {
		return AccessController.doPrivileged(
				(PrivilegedAction<String>) () -> getProperty("os.name")
		);
	}

	public void checkPath(String path) throws CorruptObjectException {
		platformChecker.checkPath(path);
	}

	public void checkPath(byte[] path) throws CorruptObjectException {
		platformChecker.checkPath(path, 0, path.length);
	}
}
