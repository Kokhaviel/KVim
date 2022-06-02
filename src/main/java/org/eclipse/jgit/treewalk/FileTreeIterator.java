/*
 * Copyright (C) 2008, Google Inc.
 * Copyright (C) 2007-2010, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org>
 * Copyright (C) 2009, Tor Arne Vestbø <torarnv@gmail.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.treewalk;

import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.util.FS;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Instant;

import static java.nio.charset.StandardCharsets.UTF_8;

public class FileTreeIterator extends WorkingTreeIterator {

	protected final File directory;
	protected final FS fs;
	protected final FileModeStrategy fileModeStrategy;

	public FileTreeIterator(Repository repo) {
		this(repo,
				repo.getConfig().get(WorkingTreeOptions.KEY).isDirNoGitLinks() ?
						NoGitlinksStrategy.INSTANCE : DefaultFileModeStrategy.INSTANCE);
	}

	public FileTreeIterator(Repository repo, FileModeStrategy fileModeStrategy) {
		this(repo.getWorkTree(), repo.getFS(),
				repo.getConfig().get(WorkingTreeOptions.KEY),
				fileModeStrategy);
		initRootIterator(repo);
	}

	public FileTreeIterator(final File root, FS fs, WorkingTreeOptions options,
							FileModeStrategy fileModeStrategy) {
		super(options);
		directory = root;
		this.fs = fs;
		this.fileModeStrategy = fileModeStrategy;
		init(entries());
	}

	protected FileTreeIterator(final WorkingTreeIterator p, final File root,
							   FS fs, FileModeStrategy fileModeStrategy) {
		super(p);
		directory = root;
		this.fs = fs;
		this.fileModeStrategy = fileModeStrategy;
		init(entries());
	}

	@Override
	public AbstractTreeIterator createSubtreeIterator(ObjectReader reader)
			throws IOException {
		if(!walksIgnoredDirectories() && isEntryIgnored()) {
			DirCacheIterator iterator = getDirCacheIterator();
			if(iterator == null) {
				return new EmptyTreeIterator(this);
			}
		}
		return enterSubtree();
	}

	protected AbstractTreeIterator enterSubtree() {
		return new FileTreeIterator(this, ((FileEntry) current()).getFile(), fs,
				fileModeStrategy);
	}

	private Entry[] entries() {
		return fs.list(directory, fileModeStrategy);
	}

	public interface FileModeStrategy {
		FileMode getMode(File f, FS.Attributes attributes);
	}

	public static class DefaultFileModeStrategy implements FileModeStrategy {

		public static final DefaultFileModeStrategy INSTANCE =
				new DefaultFileModeStrategy();

		@Override
		public FileMode getMode(File f, FS.Attributes attributes) {
			if(attributes.isSymbolicLink()) {
				return FileMode.SYMLINK;
			} else if(attributes.isDirectory()) {
				if(new File(f, Constants.DOT_GIT).exists()) {
					return FileMode.GITLINK;
				}
				return FileMode.TREE;
			} else if(attributes.isExecutable()) {
				return FileMode.EXECUTABLE_FILE;
			} else {
				return FileMode.REGULAR_FILE;
			}
		}
	}

	public static class NoGitlinksStrategy implements FileModeStrategy {

		public static final NoGitlinksStrategy INSTANCE = new NoGitlinksStrategy();

		@Override
		public FileMode getMode(File f, FS.Attributes attributes) {
			if(attributes.isSymbolicLink()) {
				return FileMode.SYMLINK;
			} else if(attributes.isDirectory()) {
				return FileMode.TREE;
			} else if(attributes.isExecutable()) {
				return FileMode.EXECUTABLE_FILE;
			} else {
				return FileMode.REGULAR_FILE;
			}
		}
	}

	public static class FileEntry extends Entry {
		private final FileMode mode;

		private final FS.Attributes attributes;

		private final FS fs;

		public FileEntry(File f, FS fs, FileModeStrategy fileModeStrategy) {
			this.fs = fs;
			f = fs.normalize(f);
			attributes = fs.getAttributes(f);
			mode = fileModeStrategy.getMode(f, attributes);
		}

		public FileEntry(File f, FS fs, FS.Attributes attributes,
						 FileModeStrategy fileModeStrategy) {
			this.fs = fs;
			this.attributes = attributes;
			f = fs.normalize(f);
			mode = fileModeStrategy.getMode(f, attributes);
		}

		@Override
		public FileMode getMode() {
			return mode;
		}

		@Override
		public String getName() {
			return attributes.getName();
		}

		@Override
		public long getLength() {
			return attributes.getLength();
		}

		@Override
		public Instant getLastModifiedInstant() {
			return attributes.getLastModifiedInstant();
		}

		@Override
		public InputStream openInputStream() throws IOException {
			if(attributes.isSymbolicLink()) {
				return new ByteArrayInputStream(fs.readSymLink(getFile())
						.getBytes(UTF_8));
			}
			return Files.newInputStream(getFile().toPath());
		}

		public File getFile() {
			return attributes.getFile();
		}
	}

	public File getDirectory() {
		return directory;
	}

	public File getEntryFile() {
		return ((FileEntry) current()).getFile();
	}

	@Override
	protected byte[] idSubmodule(Entry e) {
		return idSubmodule(getDirectory(), e);
	}

	@Override
	protected String readSymlinkTarget(Entry entry) throws IOException {
		return fs.readSymLink(getEntryFile());
	}
}
