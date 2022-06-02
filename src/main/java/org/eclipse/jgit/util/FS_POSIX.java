/*
 * Copyright (C) 2010, Robin Rosenberg and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.util;

import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_CORE_SECTION;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_SUPPORTSATOMICFILECREATION;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.errors.CommandFailedException;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FS_POSIX extends FS {
	private static final Logger LOG = LoggerFactory.getLogger(FS_POSIX.class);

	private static final String DEFAULT_GIT_LOCATION = "/usr/bin/git";

	private static final int DEFAULT_UMASK = 18;
	private volatile int umask = -1;

	private static final Map<FileStore, Boolean> CAN_HARD_LINK = new ConcurrentHashMap<>();

	private volatile AtomicFileCreation supportsAtomicFileCreation = AtomicFileCreation.UNDEFINED;

	private enum AtomicFileCreation {
		SUPPORTED, NOT_SUPPORTED, UNDEFINED
	}

	protected FS_POSIX() {
	}

	private int umask() {
		int u = umask;
		if(u == -1) {
			u = readUmask();
			umask = u;
		}
		return u;
	}

	private static int readUmask() {
		try {
			Process p = Runtime.getRuntime().exec(
					new String[] {"sh", "-c", "umask"},
					null, null);
			try(BufferedReader lineRead = new BufferedReader(
					new InputStreamReader(p.getInputStream(), SystemReader
							.getInstance().getDefaultCharset().name()))) {
				if(p.waitFor() == 0) {
					String s = lineRead.readLine();
					if(s != null && s.matches("0?\\d{3}")) {
						return Integer.parseInt(s, 8);
					}
				}
				return DEFAULT_UMASK;
			}
		} catch(Exception e) {
			return DEFAULT_UMASK;
		}
	}

	@Override
	protected File discoverGitExe() {
		String path = SystemReader.getInstance().getenv("PATH");
		File gitExe = searchPath(path, "git");

		if(SystemReader.getInstance().isMacOS()) {
			if(gitExe == null
					|| DEFAULT_GIT_LOCATION.equals(gitExe.getPath())) {
				if(searchPath(path, "bash") != null) {
					try {
						String w = readPipe(userHome(),
								new String[] {"bash", "--login", "-c", "which git"},
								SystemReader.getInstance().getDefaultCharset()
										.name());
						if(!StringUtils.isEmptyOrNull(w)) {
							gitExe = new File(w);
						}
					} catch(CommandFailedException e) {
						LOG.warn(e.getMessage());
					}
				}
			}
			if(gitExe != null
					&& DEFAULT_GIT_LOCATION.equals(gitExe.getPath())) {
				try {
					String w = readPipe(userHome(),
							new String[] {"xcode-select", "-p"},
							SystemReader.getInstance().getDefaultCharset()
									.name());
					if(StringUtils.isEmptyOrNull(w)) {
						gitExe = null;
					} else {
						File realGitExe = new File(new File(w),
								DEFAULT_GIT_LOCATION.substring(1));
						if(!realGitExe.exists()) {
							gitExe = null;
						}
					}
				} catch(CommandFailedException e) {
					gitExe = null;
				}
			}
		}

		return gitExe;
	}

	@Override
	public boolean isCaseSensitive() {
		return !SystemReader.getInstance().isMacOS();
	}

	@Override
	public boolean supportsExecute() {
		return true;
	}

	@Override
	public boolean canExecute(File f) {
		return FileUtils.canExecute(f);
	}

	@Override
	public boolean setExecute(File f, boolean canExecute) {
		if(!isFile(f))
			return false;
		if(!canExecute)
			return f.setExecutable(false, false);

		try {
			Path path = FileUtils.toPath(f);
			Set<PosixFilePermission> pset = Files.getPosixFilePermissions(path);

			pset.add(PosixFilePermission.OWNER_EXECUTE);

			int mask = umask();
			apply(pset, mask, PosixFilePermission.GROUP_EXECUTE, 1 << 3);
			apply(pset, mask, PosixFilePermission.OTHERS_EXECUTE, 1);
			Files.setPosixFilePermissions(path, pset);
			return true;
		} catch(IOException e) {
			final boolean debug = Boolean.parseBoolean(SystemReader
					.getInstance().getProperty("jgit.fs.debug"));
			if(debug)
				System.err.println(e.getMessage());
			return false;
		}
	}

	private static void apply(Set<PosixFilePermission> set,
							  int umask, PosixFilePermission perm, int test) {
		if((umask & test) == 0) {
			set.add(perm);
		} else {
			set.remove(perm);
		}
	}

	@Override
	public ProcessBuilder runInShell(String cmd, String[] args) {
		List<String> argv = new ArrayList<>(4 + args.length);
		argv.add("sh");
		argv.add("-c");
		argv.add(cmd + " \"$@\"");
		argv.add(cmd);
		argv.addAll(Arrays.asList(args));
		ProcessBuilder proc = new ProcessBuilder();
		proc.command(argv);
		return proc;
	}

	@Override
	String shellQuote(String cmd) {
		return QuotedString.BOURNE.quote(cmd);
	}

	@Override
	public ProcessResult runHookIfPresent(Repository repository, String hookName,
										  String[] args, OutputStream outRedirect, OutputStream errRedirect,
										  String stdinArgs) throws JGitInternalException {
		return internalRunHookIfPresent(repository, hookName, args, outRedirect,
				errRedirect, stdinArgs);
	}

	@Override
	public boolean retryFailedLockFileCommit() {
		return false;
	}

	@Override
	public void setHidden(File path, boolean hidden) {
	}

	@Override
	public Attributes getAttributes(File path) {
		return FileUtils.getFileAttributesPosix(this, path);
	}

	@Override
	public File normalize(File file) {
		return FileUtils.normalize(file);
	}

	@Override
	public String normalize(String name) {
		return FileUtils.normalize(name);
	}

	@Override
	public boolean supportsAtomicCreateNewFile() {
		if(supportsAtomicFileCreation == AtomicFileCreation.UNDEFINED) {
			try {
				StoredConfig config = SystemReader.getInstance().getUserConfig();
				String value = config.getString(CONFIG_CORE_SECTION, null,
						CONFIG_KEY_SUPPORTSATOMICFILECREATION);
				if(value != null) {
					supportsAtomicFileCreation = StringUtils.toBoolean(value)
							? AtomicFileCreation.SUPPORTED
							: AtomicFileCreation.NOT_SUPPORTED;
				} else {
					supportsAtomicFileCreation = AtomicFileCreation.SUPPORTED;
				}
			} catch(IOException | ConfigInvalidException e) {
				LOG.warn(JGitText.get().assumeAtomicCreateNewFile, e);
				supportsAtomicFileCreation = AtomicFileCreation.SUPPORTED;
			}
		}
		return supportsAtomicFileCreation == AtomicFileCreation.SUPPORTED;
	}

	@Override
	public LockToken createNewFileAtomic(File file) throws IOException {
		Path path;
		try {
			path = file.toPath();
			Files.createFile(path);
		} catch(FileAlreadyExistsException | InvalidPathException e) {
			return token(false, null);
		}
		if(supportsAtomicCreateNewFile()) {
			return token(true, null);
		}
		Path link = null;
		FileStore store;
		try {
			store = Files.getFileStore(path);
		} catch(SecurityException e) {
			return token(true, null);
		}
		try {
			Boolean canLink = CAN_HARD_LINK.computeIfAbsent(store,
					s -> Boolean.TRUE);
			if(Boolean.FALSE.equals(canLink)) {
				return token(true, null);
			}
			link = Files.createLink(Paths.get(uniqueLinkPath(file)), path);
			Integer nlink = (Integer) (Files.getAttribute(path,
					"unix:nlink"));
			if(nlink > 2) {
				LOG.warn(MessageFormat.format(
						JGitText.get().failedAtomicFileCreation, path, nlink));
				return token(false, link);
			} else if(nlink < 2) {
				CAN_HARD_LINK.put(store, Boolean.FALSE);
			}
			return token(true, link);
		} catch(UnsupportedOperationException | IllegalArgumentException
				| FileSystemException | SecurityException e) {
			CAN_HARD_LINK.put(store, Boolean.FALSE);
			return token(true, link);
		}
	}

	private static LockToken token(boolean created, @Nullable Path p) {
		return ((p != null) && Files.exists(p))
				? new LockToken(created, Optional.of(p))
				: new LockToken(created, Optional.empty());
	}

	private static String uniqueLinkPath(File file) {
		UUID id = UUID.randomUUID();
		return file.getAbsolutePath() + "."
				+ Long.toHexString(id.getMostSignificantBits())
				+ Long.toHexString(id.getLeastSignificantBits());
	}
}
