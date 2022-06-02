/*
 * Copyright (C) 2007, Dave Watson <dwatson@mimvista.com>
 * Copyright (C) 2008-2010, Google Inc.
 * Copyright (C) 2006-2010, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2006-2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.file;

import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.attributes.AttributesNode;
import org.eclipse.jgit.attributes.AttributesNodeProvider;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.events.IndexChangedEvent;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.internal.storage.file.ObjectDirectory.AlternateHandle;
import org.eclipse.jgit.internal.storage.file.ObjectDirectory.AlternateRepository;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.lib.CoreConfig.HideDotFiles;
import org.eclipse.jgit.lib.CoreConfig.SymLinks;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.storage.pack.PackConfig;
import org.eclipse.jgit.util.FileUtils;
import org.eclipse.jgit.util.StringUtils;
import org.eclipse.jgit.util.SystemReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.MessageFormat;
import java.text.ParseException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class FileRepository extends Repository {
	private static final Logger LOG = LoggerFactory
			.getLogger(FileRepository.class);

	private final FileBasedConfig repoConfig;
	private final RefDatabase refs;
	private final ObjectDirectory objectDatabase;

	public FileRepository(File gitDir) throws IOException {
		this(new FileRepositoryBuilder().setGitDir(gitDir).setup());
	}

	public FileRepository(String gitDir) throws IOException {
		this(new File(gitDir));
	}

	public FileRepository(BaseRepositoryBuilder<?, ?> options) throws IOException {
		super(options);
		StoredConfig userConfig;
		try {
			userConfig = SystemReader.getInstance().getUserConfig();
		} catch(ConfigInvalidException e) {
			LOG.error(e.getMessage(), e);
			throw new IOException(e.getMessage(), e);
		}
		repoConfig = new FileBasedConfig(userConfig, getFS().resolve(
				getDirectory(), Constants.CONFIG),
				getFS());
		loadRepoConfig();

		repoConfig.addChangeListener(this::fireEvent);

		final long repositoryFormatVersion = getConfig().getLong(
				ConfigConstants.CONFIG_CORE_SECTION, null,
				ConfigConstants.CONFIG_KEY_REPO_FORMAT_VERSION, 0);

		String reftype = repoConfig.getString(
				ConfigConstants.CONFIG_EXTENSIONS_SECTION, null,
				ConfigConstants.CONFIG_KEY_REF_STORAGE);
		if(repositoryFormatVersion >= 1 && reftype != null) {
			if(StringUtils.equalsIgnoreCase(reftype,
					ConfigConstants.CONFIG_REF_STORAGE_REFTABLE)) {
				refs = new FileReftableDatabase(this);
			} else {
				throw new IOException(JGitText.get().unknownRepositoryFormat);
			}
		} else {
			refs = new RefDirectory(this);
		}

		objectDatabase = new ObjectDirectory(repoConfig, options.getObjectDirectory(),
				options.getAlternateObjectDirectories(), getFS(), new File(getDirectory(), Constants.SHALLOW));

		if(objectDatabase.exists()) {
			if(repositoryFormatVersion > 1)
				throw new IOException(MessageFormat.format(JGitText.get().unknownRepositoryFormat2,
						repositoryFormatVersion));
		}
		isBare();
	}

	private void loadRepoConfig() throws IOException {
		try {
			repoConfig.load();
		} catch(ConfigInvalidException e) {
			throw new IOException(JGitText.get().unknownRepositoryFormat, e);
		}
	}

	@Override
	public void create(boolean bare) throws IOException {
		final FileBasedConfig cfg = getConfig();
		if(cfg.getFile().exists()) {
			throw new IllegalStateException(MessageFormat.format(
					JGitText.get().repositoryAlreadyExists, getDirectory()));
		}
		FileUtils.mkdirs(getDirectory(), true);
		HideDotFiles hideDotFiles = getConfig().getEnum(
				ConfigConstants.CONFIG_CORE_SECTION, null,
				ConfigConstants.CONFIG_KEY_HIDEDOTFILES,
				HideDotFiles.DOTGITONLY);
		if(hideDotFiles != HideDotFiles.FALSE && !isBare()
				&& getDirectory().getName().startsWith("."))
			getFS().setHidden(getDirectory(), true);
		refs.create();
		objectDatabase.create();

		FileUtils.mkdir(new File(getDirectory(), "branches"));
		FileUtils.mkdir(new File(getDirectory(), "hooks"));

		RefUpdate head = updateRef(Constants.HEAD);
		head.disableRefLog();
		head.link(Constants.R_HEADS + getInitialBranch());

		final boolean fileMode;
		if(getFS().supportsExecute()) {
			File tmp = File.createTempFile("try", "execute", getDirectory());

			getFS().setExecute(tmp, true);
			final boolean on = getFS().canExecute(tmp);

			getFS().setExecute(tmp, false);
			final boolean off = getFS().canExecute(tmp);
			FileUtils.delete(tmp);

			fileMode = on && !off;
		} else {
			fileMode = false;
		}

		SymLinks symLinks = SymLinks.FALSE;
		if(getFS().supportsSymlinks()) {
			File tmp = new File(getDirectory(), "tmplink");
			try {
				getFS().createSymLink(tmp, "target");
				symLinks = null;
				FileUtils.delete(tmp);
			} catch(IOException ignored) {
			}
		}
		if(symLinks != null)
			cfg.setString(ConfigConstants.CONFIG_CORE_SECTION, null,
					ConfigConstants.CONFIG_KEY_SYMLINKS, symLinks.name()
							.toLowerCase(Locale.ROOT));
		cfg.setInt(ConfigConstants.CONFIG_CORE_SECTION, null,
				ConfigConstants.CONFIG_KEY_REPO_FORMAT_VERSION, 0);
		cfg.setBoolean(ConfigConstants.CONFIG_CORE_SECTION, null,
				ConfigConstants.CONFIG_KEY_FILEMODE, fileMode);
		if(bare)
			cfg.setBoolean(ConfigConstants.CONFIG_CORE_SECTION, null,
					ConfigConstants.CONFIG_KEY_BARE, true);
		cfg.setBoolean(ConfigConstants.CONFIG_CORE_SECTION, null,
				ConfigConstants.CONFIG_KEY_LOGALLREFUPDATES, !bare);
		if(SystemReader.getInstance().isMacOS())
			cfg.setBoolean(ConfigConstants.CONFIG_CORE_SECTION, null,
					ConfigConstants.CONFIG_KEY_PRECOMPOSEUNICODE, true);
		if(!bare) {
			File workTree = getWorkTree();
			if(!getDirectory().getParentFile().equals(workTree)) {
				cfg.setString(ConfigConstants.CONFIG_CORE_SECTION, null,
						ConfigConstants.CONFIG_KEY_WORKTREE, getWorkTree()
								.getAbsolutePath());
				LockFile dotGitLockFile = new LockFile(new File(workTree,
						Constants.DOT_GIT));
				try {
					if(dotGitLockFile.lock()) {
						dotGitLockFile.write(Constants.encode(Constants.GITDIR
								+ getDirectory().getAbsolutePath()));
						dotGitLockFile.commit();
					}
				} finally {
					dotGitLockFile.unlock();
				}
			}
		}
		cfg.save();
	}

	public File getObjectsDirectory() {
		return objectDatabase.getDirectory();
	}

	@Override
	public ObjectDirectory getObjectDatabase() {
		return objectDatabase;
	}

	@Override
	public RefDatabase getRefDatabase() {
		return refs;
	}

	@Override
	public String getIdentifier() {
		File directory = getDirectory();
		if(directory != null) {
			return directory.getPath();
		}
		throw new IllegalStateException();
	}

	@Override
	public FileBasedConfig getConfig() {
		try {
			SystemReader.getInstance().getUserConfig();
			if(repoConfig.isOutdated()) {
				loadRepoConfig();
			}
		} catch(IOException | ConfigInvalidException e) {
			throw new RuntimeException(e);
		}
		return repoConfig;
	}

	@Override
	public Set<ObjectId> getAdditionalHaves() throws IOException {
		return getAdditionalHaves(null);
	}

	private Set<ObjectId> getAdditionalHaves(Set<AlternateHandle.Id> skips)
			throws IOException {
		HashSet<ObjectId> r = new HashSet<>();
		skips = objectDatabase.addMe(skips);
		for(AlternateHandle d : objectDatabase.myAlternates()) {
			if(d instanceof AlternateRepository && !skips.contains(d.getId())) {
				FileRepository repo;

				repo = ((AlternateRepository) d).repository;
				for(Ref ref : repo.getRefDatabase().getRefs()) {
					if(ref.getObjectId() != null)
						r.add(ref.getObjectId());
					if(ref.getPeeledObjectId() != null)
						r.add(ref.getPeeledObjectId());
				}
				r.addAll(repo.getAdditionalHaves(skips));
			}
		}
		return r;
	}

	@Override
	public void notifyIndexChanged(boolean internal) {
		fireEvent(new IndexChangedEvent());
	}

	@Override
	public ReflogReader getReflogReader(String refName) throws IOException {
		if(refs instanceof FileReftableDatabase) {
			return ((FileReftableDatabase) refs).getReflogReader(refName);
		}

		Ref ref = findRef(refName);
		if(ref == null) {
			return null;
		}
		return new ReflogReaderImpl(this, ref.getName());
	}

	@Override
	public AttributesNodeProvider createAttributesNodeProvider() {
		return new AttributesNodeProviderImpl(this);
	}

	static class AttributesNodeProviderImpl implements AttributesNodeProvider {

		private AttributesNode infoAttributesNode;
		private AttributesNode globalAttributesNode;

		protected AttributesNodeProviderImpl(Repository repo) {
			infoAttributesNode = new InfoAttributesNode(repo);
			globalAttributesNode = new GlobalAttributesNode(repo);
		}

		@Override
		public AttributesNode getInfoAttributesNode() throws IOException {
			if(infoAttributesNode instanceof InfoAttributesNode)
				infoAttributesNode = ((InfoAttributesNode) infoAttributesNode)
						.load();
			return infoAttributesNode;
		}

		@Override
		public AttributesNode getGlobalAttributesNode() throws IOException {
			if(globalAttributesNode instanceof GlobalAttributesNode)
				globalAttributesNode = ((GlobalAttributesNode) globalAttributesNode)
						.load();
			return globalAttributesNode;
		}

		static void loadRulesFromFile(AttributesNode r, File attrs)
				throws IOException {
			if(attrs.exists()) {
				try(FileInputStream in = new FileInputStream(attrs)) {
					r.parse(in);
				}
			}
		}

	}

	private boolean shouldAutoDetach() {
		return getConfig().getBoolean(ConfigConstants.CONFIG_GC_SECTION,
				ConfigConstants.CONFIG_KEY_AUTODETACH, true);
	}

	@Override
	public void autoGC(ProgressMonitor monitor) {
		GC gc = new GC(this);
		gc.setPackConfig(new PackConfig(this));
		gc.setProgressMonitor(monitor);
		gc.setAuto(true);
		gc.setBackground(shouldAutoDetach());
		try {
			gc.gc();
		} catch(ParseException | IOException e) {
			throw new JGitInternalException(JGitText.get().gcFailed, e);
		}
	}

}
