/*
 * Copyright (C) 2011, GitHub Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.submodule;

import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.eclipse.jgit.util.FS;

import java.io.File;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Map;

public class SubmoduleWalk implements AutoCloseable {

	public enum IgnoreSubmoduleMode {
		ALL,
		DIRTY,
		UNTRACKED,
		NONE
	}

	public static SubmoduleWalk forIndex(Repository repository)
			throws IOException {
		SubmoduleWalk generator = new SubmoduleWalk(repository);
		try {
			DirCache index = repository.readDirCache();
			generator.setTree(new DirCacheIterator(index));
		} catch(IOException e) {
			generator.close();
			throw e;
		}
		return generator;
	}

	public static File getSubmoduleDirectory(final Repository parent,
											 final String path) {
		return new File(parent.getWorkTree(), path);
	}

	public static Repository getSubmoduleRepository(final File parent,
													final String path, FS fs) throws IOException {
		return getSubmoduleRepository(parent, path, fs,
				new RepositoryBuilder());
	}

	public static Repository getSubmoduleRepository(File parent, String path,
													FS fs, BaseRepositoryBuilder<?, ? extends Repository> builder)
			throws IOException {
		File subWorkTree = new File(parent, path);
		if(!subWorkTree.isDirectory()) {
			return null;
		}
		try {
			return builder
					.setMustExist(true)
					.setFS(fs)
					.setWorkTree(subWorkTree)
					.build();
		} catch(RepositoryNotFoundException e) {
			return null;
		}
	}

	public static String getSubmoduleRemoteUrl(final Repository parent,
											   final String url) throws IOException {
		if(!url.startsWith("./") && !url.startsWith("../"))
			return url;

		String remoteName = null;
		Ref ref = parent.exactRef(Constants.HEAD);
		if(ref != null) {
			if(ref.isSymbolic())
				ref = ref.getLeaf();
			remoteName = parent.getConfig().getString(
					ConfigConstants.CONFIG_BRANCH_SECTION,
					Repository.shortenRefName(ref.getName()),
					ConfigConstants.CONFIG_KEY_REMOTE);
		}

		if(remoteName == null)
			remoteName = Constants.DEFAULT_REMOTE_NAME;

		String remoteUrl = parent.getConfig().getString(
				ConfigConstants.CONFIG_REMOTE_SECTION, remoteName,
				ConfigConstants.CONFIG_KEY_URL);

		if(remoteUrl == null) {
			remoteUrl = parent.getWorkTree().getAbsolutePath();
			if('\\' == File.separatorChar)
				remoteUrl = remoteUrl.replace('\\', '/');
		}

		if(remoteUrl.charAt(remoteUrl.length() - 1) == '/')
			remoteUrl = remoteUrl.substring(0, remoteUrl.length() - 1);

		char separator = '/';
		String submoduleUrl = url;
		while(submoduleUrl.length() > 0) {
			if(submoduleUrl.startsWith("./"))
				submoduleUrl = submoduleUrl.substring(2);
			else if(submoduleUrl.startsWith("../")) {
				int lastSeparator = remoteUrl.lastIndexOf('/');
				if(lastSeparator < 1) {
					lastSeparator = remoteUrl.lastIndexOf(':');
					separator = ':';
				}
				if(lastSeparator < 1)
					throw new IOException(MessageFormat.format(
							JGitText.get().submoduleParentRemoteUrlInvalid,
							remoteUrl));
				remoteUrl = remoteUrl.substring(0, lastSeparator);
				submoduleUrl = submoduleUrl.substring(3);
			} else
				break;
		}
		return remoteUrl + separator + submoduleUrl;
	}

	private final Repository repository;
	private final TreeWalk walk;
	private final StoredConfig repoConfig;
	private AbstractTreeIterator rootTree;
	private Config modulesConfig;
	private String path;
	private Map<String, String> pathToName;
	private RepositoryBuilderFactory factory;

	public SubmoduleWalk(Repository repository) throws IOException {
		this.repository = repository;
		repoConfig = repository.getConfig();
		walk = new TreeWalk(repository);
		walk.setRecursive(true);
	}

	public SubmoduleWalk loadModulesConfig() throws IOException, ConfigInvalidException {
		if(rootTree == null) {
			File modulesFile = new File(repository.getWorkTree(),
					Constants.DOT_GIT_MODULES);
			FileBasedConfig config = new FileBasedConfig(modulesFile,
					repository.getFS());
			config.load();
			modulesConfig = config;
			loadPathNames();
		} else {
			try(TreeWalk configWalk = new TreeWalk(repository)) {
				configWalk.addTree(rootTree);

				int idx;
				for(idx = 0; !rootTree.first(); idx++) {
					rootTree.back(1);
				}

				try {
					configWalk.setRecursive(false);
					PathFilter filter = PathFilter.create(Constants.DOT_GIT_MODULES);
					configWalk.setFilter(filter);
					while(configWalk.next()) {
						if(filter.isDone(configWalk)) {
							modulesConfig = new BlobBasedConfig(null, repository,
									configWalk.getObjectId(0));
							loadPathNames();
							return this;
						}
					}
					modulesConfig = new Config();
					pathToName = null;
				} finally {
					if(idx > 0)
						rootTree.next(idx);
				}
			}
		}
		return this;
	}

	private void loadPathNames() {
		pathToName = null;
		if(modulesConfig != null) {
			HashMap<String, String> pathNames = new HashMap<>();
			for(String name : modulesConfig
					.getSubsections(ConfigConstants.CONFIG_SUBMODULE_SECTION)) {
				pathNames.put(modulesConfig.getString(
						ConfigConstants.CONFIG_SUBMODULE_SECTION, name,
						ConfigConstants.CONFIG_KEY_PATH), name);
			}
			pathToName = pathNames;
		}
	}

	private void lazyLoadModulesConfig() throws IOException, ConfigInvalidException {
		if(modulesConfig == null) {
			loadModulesConfig();
		}
	}

	private String getModuleName(String modulePath) {
		String name = pathToName != null ? pathToName.get(modulePath) : null;
		return name != null ? name : modulePath;
	}

	public SubmoduleWalk setFilter(TreeFilter filter) {
		walk.setFilter(filter);
		return this;
	}

	public SubmoduleWalk setTree(AbstractTreeIterator iterator)
			throws CorruptObjectException {
		walk.addTree(iterator);
		return this;
	}

	public SubmoduleWalk setTree(AnyObjectId treeId) throws IOException {
		walk.addTree(treeId);
		return this;
	}

	public File getDirectory() {
		return getSubmoduleDirectory(repository, path);
	}

	public boolean next() throws IOException {
		while(walk.next()) {
			if(FileMode.GITLINK != walk.getFileMode(0))
				continue;
			path = walk.getPathString();
			return true;
		}
		path = null;
		return false;
	}

	public String getPath() {
		return path;
	}

	public void setBuilderFactory(RepositoryBuilderFactory factory) {
		this.factory = factory;
	}

	private BaseRepositoryBuilder<?, ? extends Repository> getBuilder() {
		return factory != null ? factory.get() : new RepositoryBuilder();
	}

	public String getModuleName() throws IOException, ConfigInvalidException {
		lazyLoadModulesConfig();
		return getModuleName(path);
	}

	public ObjectId getObjectId() {
		return walk.getObjectId(0);
	}

	public String getModulesPath() throws IOException, ConfigInvalidException {
		lazyLoadModulesConfig();
		return modulesConfig.getString(ConfigConstants.CONFIG_SUBMODULE_SECTION,
				getModuleName(), ConfigConstants.CONFIG_KEY_PATH);
	}

	public String getConfigUrl() throws IOException, ConfigInvalidException {
		return repoConfig.getString(ConfigConstants.CONFIG_SUBMODULE_SECTION,
				getModuleName(), ConfigConstants.CONFIG_KEY_URL);
	}

	public String getModulesUrl() throws IOException, ConfigInvalidException {
		lazyLoadModulesConfig();
		return modulesConfig.getString(ConfigConstants.CONFIG_SUBMODULE_SECTION,
				getModuleName(), ConfigConstants.CONFIG_KEY_URL);
	}

	public String getConfigUpdate() throws IOException, ConfigInvalidException {
		return repoConfig.getString(ConfigConstants.CONFIG_SUBMODULE_SECTION,
				getModuleName(), ConfigConstants.CONFIG_KEY_UPDATE);
	}

	public String getModulesUpdate() throws IOException, ConfigInvalidException {
		lazyLoadModulesConfig();
		return modulesConfig.getString(ConfigConstants.CONFIG_SUBMODULE_SECTION,
				getModuleName(), ConfigConstants.CONFIG_KEY_UPDATE);
	}

	public IgnoreSubmoduleMode getModulesIgnore() throws IOException,
			ConfigInvalidException {
		IgnoreSubmoduleMode mode = repoConfig.getEnum(
				IgnoreSubmoduleMode.values(),
				ConfigConstants.CONFIG_SUBMODULE_SECTION, getModuleName(),
				ConfigConstants.CONFIG_KEY_IGNORE, null);
		if(mode != null) {
			return mode;
		}
		lazyLoadModulesConfig();
		return modulesConfig.getEnum(IgnoreSubmoduleMode.values(),
				ConfigConstants.CONFIG_SUBMODULE_SECTION, getModuleName(),
				ConfigConstants.CONFIG_KEY_IGNORE, IgnoreSubmoduleMode.NONE);
	}

	public Repository getRepository() throws IOException {
		return getSubmoduleRepository(repository.getWorkTree(), path,
				repository.getFS(), getBuilder());
	}

	public String getRemoteUrl() throws IOException, ConfigInvalidException {
		String url = getModulesUrl();
		return url != null ? getSubmoduleRemoteUrl(repository, url) : null;
	}

	@Override
	public void close() {
		walk.close();
	}
}
