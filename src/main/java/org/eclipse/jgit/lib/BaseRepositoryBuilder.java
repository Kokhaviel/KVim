/*
 * Copyright (C) 2010, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.lib;

import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_CORE_SECTION;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_BARE;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_WORKTREE;
import static org.eclipse.jgit.lib.Constants.DOT_GIT;
import static org.eclipse.jgit.lib.Constants.GIT_ALTERNATE_OBJECT_DIRECTORIES_KEY;
import static org.eclipse.jgit.lib.Constants.GIT_CEILING_DIRECTORIES_KEY;
import static org.eclipse.jgit.lib.Constants.GIT_DIR_KEY;
import static org.eclipse.jgit.lib.Constants.GIT_INDEX_FILE_KEY;
import static org.eclipse.jgit.lib.Constants.GIT_OBJECT_DIRECTORY_KEY;
import static org.eclipse.jgit.lib.Constants.GIT_WORK_TREE_KEY;

import java.io.File;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.LinkedList;
import java.util.List;

import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.api.errors.InvalidRefNameException;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.IO;
import org.eclipse.jgit.util.RawParseUtils;
import org.eclipse.jgit.util.StringUtils;
import org.eclipse.jgit.util.SystemReader;

public class BaseRepositoryBuilder<B extends BaseRepositoryBuilder, R extends Repository> {
	private static boolean isSymRef(byte[] ref) {
		if(ref.length < 9) return false;
		return ref[0] == 'g' && ref[1] == 'i'
				&& ref[2] == 't' && ref[3] == 'd' && ref[4] == 'i'
				&& ref[5] == 'r' && ref[6] == ':' && ref[7] == ' ';
	}

	private static File getSymRef(File workTree, File dotGit, FS fs)
			throws IOException {
		byte[] content = IO.readFully(dotGit);
		if(!isSymRef(content)) {
			throw new IOException(MessageFormat.format(
					JGitText.get().invalidGitdirRef, dotGit.getAbsolutePath()));
		}

		int pathStart = 8;
		int lineEnd = RawParseUtils.nextLF(content, pathStart);
		while(content[lineEnd - 1] == '\n' ||
				(content[lineEnd - 1] == '\r'
						&& SystemReader.getInstance().isWindows())) {
			lineEnd--;
		}
		if(lineEnd == pathStart) {
			throw new IOException(MessageFormat.format(
					JGitText.get().invalidGitdirRef, dotGit.getAbsolutePath()));
		}

		String gitdirPath = RawParseUtils.decode(content, pathStart, lineEnd);
		File gitdirFile = fs.resolve(workTree, gitdirPath);
		if(gitdirFile.isAbsolute()) {
			return gitdirFile;
		}
		return new File(workTree, gitdirPath).getCanonicalFile();
	}

	private FS fs;
	private File gitDir;
	private File objectDirectory;
	private List<File> alternateObjectDirectories;
	private File indexFile;
	private File workTree;
	private String initialBranch = Constants.MASTER;
	private List<File> ceilingDirectories;
	private boolean bare;
	private boolean mustExist;
	private Config config;

	public B setFS(FS fs) {
		this.fs = fs;
		return self();
	}

	public FS getFS() {
		return fs;
	}

	public B setGitDir(File gitDir) {
		this.gitDir = gitDir;
		this.config = null;
		return self();
	}

	public File getGitDir() {
		return gitDir;
	}

	public B setObjectDirectory(File objectDirectory) {
		this.objectDirectory = objectDirectory;
		return self();
	}

	public File getObjectDirectory() {
		return objectDirectory;
	}

	public B addAlternateObjectDirectory(File other) {
		if(other != null) {
			if(alternateObjectDirectories == null)
				alternateObjectDirectories = new LinkedList<>();
			alternateObjectDirectories.add(other);
		}
		return self();
	}

	public File[] getAlternateObjectDirectories() {
		final List<File> alts = alternateObjectDirectories;
		if(alts == null)
			return null;
		return alts.toArray(new File[0]);
	}

	public B setBare() {
		setIndexFile(null);
		setWorkTree(null);
		bare = true;
		return self();
	}

	public boolean isBare() {
		return bare;
	}

	public B setMustExist(boolean mustExist) {
		this.mustExist = mustExist;
		return self();
	}

	public boolean isMustExist() {
		return mustExist;
	}

	public B setWorkTree(File workTree) {
		this.workTree = workTree;
		return self();
	}

	public File getWorkTree() {
		return workTree;
	}

	public B setIndexFile(File indexFile) {
		this.indexFile = indexFile;
		return self();
	}

	public File getIndexFile() {
		return indexFile;
	}

	public B setInitialBranch(String branch) throws InvalidRefNameException {
		if(StringUtils.isEmptyOrNull(branch)) {
			this.initialBranch = Constants.MASTER;
		} else {
			if(!Repository.isValidRefName(Constants.R_HEADS + branch)) {
				throw new InvalidRefNameException(MessageFormat
						.format(JGitText.get().branchNameInvalid, branch));
			}
			this.initialBranch = branch;
		}
		return self();
	}

	public @NonNull String getInitialBranch() {
		return initialBranch;
	}

	public B readEnvironment() {
		return readEnvironment(SystemReader.getInstance());
	}

	public B readEnvironment(SystemReader sr) {
		if(getGitDir() == null) {
			String val = sr.getenv(GIT_DIR_KEY);
			if(val != null)
				setGitDir(new File(val));
		}

		if(getObjectDirectory() == null) {
			String val = sr.getenv(GIT_OBJECT_DIRECTORY_KEY);
			if(val != null)
				setObjectDirectory(new File(val));
		}

		if(getAlternateObjectDirectories() == null) {
			String val = sr.getenv(GIT_ALTERNATE_OBJECT_DIRECTORIES_KEY);
			if(val != null) {
				for(String path : val.split(File.pathSeparator))
					addAlternateObjectDirectory(new File(path));
			}
		}

		if(getWorkTree() == null) {
			String val = sr.getenv(GIT_WORK_TREE_KEY);
			if(val != null)
				setWorkTree(new File(val));
		}

		if(getIndexFile() == null) {
			String val = sr.getenv(GIT_INDEX_FILE_KEY);
			if(val != null)
				setIndexFile(new File(val));
		}

		if(ceilingDirectories == null) {
			String val = sr.getenv(GIT_CEILING_DIRECTORIES_KEY);
			if(val != null) {
				for(String path : val.split(File.pathSeparator))
					addCeilingDirectory(new File(path));
			}
		}

		return self();
	}

	public B addCeilingDirectory(File root) {
		if(root != null) {
			if(ceilingDirectories == null)
				ceilingDirectories = new LinkedList<>();
			ceilingDirectories.add(root);
		}
		return self();
	}

	public B setup() throws IllegalArgumentException, IOException {
		requireGitDirOrWorkTree();
		setupGitDir();
		setupWorkTree();
		setupInternals();
		return self();
	}

	@SuppressWarnings("unchecked")
	public R build() throws IOException {
		R repo = (R) new FileRepository(setup());
		if(isMustExist() && !repo.getObjectDatabase().exists())
			throw new RepositoryNotFoundException(getGitDir());
		return repo;
	}

	protected void requireGitDirOrWorkTree() {
		if(getGitDir() == null && getWorkTree() == null)
			throw new IllegalArgumentException(
					JGitText.get().eitherGitDirOrWorkTreeRequired);
	}

	protected void setupGitDir() throws IOException {
		if(getGitDir() == null && getWorkTree() != null) {
			File dotGit = new File(getWorkTree(), DOT_GIT);
			if(!dotGit.isFile())
				setGitDir(dotGit);
			else
				setGitDir(getSymRef(getWorkTree(), dotGit, safeFS()));
		}
	}

	protected void setupWorkTree() throws IOException {
		if(getFS() == null)
			setFS(FS.DETECTED);

		if(!isBare() && getWorkTree() == null)
			setWorkTree(guessWorkTreeOrFail());

		if(!isBare()) {
			if(getGitDir() == null)
				setGitDir(getWorkTree().getParentFile());
			if(getIndexFile() == null)
				setIndexFile(new File(getGitDir(), "index"));
		}
	}

	protected void setupInternals() {
		if(getObjectDirectory() == null && getGitDir() != null)
			setObjectDirectory(safeFS().resolve(getGitDir(), Constants.OBJECTS));
	}

	protected Config getConfig() throws IOException {
		if(config == null)
			config = loadConfig();
		return config;
	}

	protected Config loadConfig() throws IOException {
		if(getGitDir() != null) {
			File path = safeFS().resolve(getGitDir(), Constants.CONFIG);
			FileBasedConfig cfg = new FileBasedConfig(path, safeFS());
			try {
				cfg.load();
			} catch(ConfigInvalidException err) {
				throw new IllegalArgumentException(MessageFormat.format(
						JGitText.get().repositoryConfigFileInvalid, path
								.getAbsolutePath(), err.getMessage()));
			}
			return cfg;
		}
		return new Config();
	}

	private File guessWorkTreeOrFail() throws IOException {
		final Config cfg = getConfig();

		String path = cfg.getString(CONFIG_CORE_SECTION, null,
				CONFIG_KEY_WORKTREE);
		if(path != null)
			return safeFS().resolve(getGitDir(), path).getCanonicalFile();

		if(cfg.getString(CONFIG_CORE_SECTION, null, CONFIG_KEY_BARE) != null) {
			if(cfg.getBoolean(CONFIG_CORE_SECTION, CONFIG_KEY_BARE, true)) {
				setBare();
				return null;
			}
			return getGitDir().getParentFile();
		}

		if(getGitDir().getName().equals(DOT_GIT)) {
			return getGitDir().getParentFile();
		}

		setBare();
		return null;
	}

	protected FS safeFS() {
		return getFS() != null ? getFS() : FS.DETECTED;
	}

	@SuppressWarnings("unchecked")
	protected final B self() {
		return (B) this;
	}
}
