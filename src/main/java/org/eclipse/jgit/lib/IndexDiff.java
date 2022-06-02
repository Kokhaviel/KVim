/*
 * Copyright (C) 2007, Dave Watson <dwatson@mimvista.com>
 * Copyright (C) 2007-2008, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2010, Jens Baumgart <jens.baumgart@sap.com>
 * Copyright (C) 2013, Robin Stocker <robin@nibor.org>
 * Copyright (C) 2014, Axel Richard <axel.richard@obeo.fr> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.lib;

import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.errors.StopWalkException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.submodule.SubmoduleWalk;
import org.eclipse.jgit.submodule.SubmoduleWalk.IgnoreSubmoduleMode;
import org.eclipse.jgit.treewalk.*;
import org.eclipse.jgit.treewalk.TreeWalk.OperationType;
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.IndexDiffFilter;
import org.eclipse.jgit.treewalk.filter.SkipWorkTreeFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.text.MessageFormat;
import java.util.*;

public class IndexDiff {

	public enum StageState {

		BOTH_DELETED(1),
		ADDED_BY_US(2),
		DELETED_BY_THEM(3),
		ADDED_BY_THEM(4),
		DELETED_BY_US(5),
		BOTH_ADDED(6),
		BOTH_MODIFIED(7);

		private final int stageMask;

		StageState(int stageMask) {
			this.stageMask = stageMask;
		}

		int getStageMask() {
			return stageMask;
		}

		static StageState fromMask(int stageMask) {
			switch(stageMask) {
				case 1:
					return BOTH_DELETED;
				case 2:
					return ADDED_BY_US;
				case 3:
					return DELETED_BY_THEM;
				case 4:
					return ADDED_BY_THEM;
				case 5:
					return DELETED_BY_US;
				case 6:
					return BOTH_ADDED;
				case 7:
					return BOTH_MODIFIED;
				default:
					return null;
			}
		}
	}

	private static final class ProgressReportingFilter extends TreeFilter {

		private final ProgressMonitor monitor;
		private int count = 0;
		private int stepSize;
		private final int total;

		private ProgressReportingFilter(ProgressMonitor monitor, int total) {
			this.monitor = monitor;
			this.total = total;
			stepSize = total / 100;
			if(stepSize == 0)
				stepSize = 1000;
		}

		@Override
		public boolean shouldBeRecursive() {
			return false;
		}

		@Override
		public boolean include(TreeWalk walker) {
			count++;
			if(count % stepSize == 0) {
				if(count <= total)
					monitor.update(stepSize);
				if(monitor.isCancelled())
					throw StopWalkException.INSTANCE;
			}
			return true;
		}

		@Override
		public TreeFilter clone() {
			throw new IllegalStateException(
					"Do not clone this kind of filter: "
							+ getClass().getName());
		}
	}

	private static final int TREE = 0;
	private static final int INDEX = 1;
	private static final int WORKDIR = 2;

	private final Repository repository;
	private final AnyObjectId tree;
	private TreeFilter filter = null;
	private final WorkingTreeIterator initialWorkingTreeIterator;
	private final Set<String> added = new HashSet<>();
	private final Set<String> changed = new HashSet<>();
	private final Set<String> removed = new HashSet<>();
	private final Set<String> missing = new HashSet<>();
	private final Set<String> missingSubmodules = new HashSet<>();
	private final Set<String> modified = new HashSet<>();
	private final Set<String> untracked = new HashSet<>();
	private final Map<String, StageState> conflicts = new HashMap<>();
	private Set<String> ignored;
	private final Map<String, IndexDiff> submoduleIndexDiffs = new HashMap<>();
	private IgnoreSubmoduleMode ignoreSubmoduleMode = null;
	private final Map<FileMode, Set<String>> fileModes = new HashMap<>();

	public IndexDiff(Repository repository, String revstr,
					 WorkingTreeIterator workingTreeIterator) throws IOException {
		this(repository, repository.resolve(revstr), workingTreeIterator);
	}

	public IndexDiff(Repository repository, ObjectId objectId,
					 WorkingTreeIterator workingTreeIterator) throws IOException {
		this.repository = repository;
		if(objectId != null) {
			try(RevWalk rw = new RevWalk(repository)) {
				tree = rw.parseTree(objectId);
			}
		} else {
			tree = null;
		}
		this.initialWorkingTreeIterator = workingTreeIterator;
	}

	public void setIgnoreSubmoduleMode(IgnoreSubmoduleMode mode) {
		this.ignoreSubmoduleMode = mode;
	}

	public interface WorkingTreeIteratorFactory {

		WorkingTreeIterator getWorkingTreeIterator(Repository repo);
	}

	private final WorkingTreeIteratorFactory wTreeIt = FileTreeIterator::new;

	public void setFilter(TreeFilter filter) {
		this.filter = filter;
	}

	public boolean diff() throws IOException {
		return diff(null);
	}

	public boolean diff(RepositoryBuilderFactory factory)
			throws IOException {
		return diff(null, 0, 0, "", factory);
	}

	public boolean diff(final ProgressMonitor monitor, int estWorkTreeSize,
						int estIndexSize, final String title)
			throws IOException {
		return diff(monitor, estWorkTreeSize, estIndexSize, title, null);
	}

	public boolean diff(ProgressMonitor monitor, int estWorkTreeSize,
						int estIndexSize, String title, RepositoryBuilderFactory factory)
			throws IOException {
		DirCache dirCache = repository.readDirCache();

		IndexDiffFilter indexDiffFilter;
		try(TreeWalk treeWalk = new TreeWalk(repository)) {
			treeWalk.setOperationType(OperationType.CHECKIN_OP);
			treeWalk.setRecursive(true);
			if(tree != null)
				treeWalk.addTree(tree);
			else
				treeWalk.addTree(new EmptyTreeIterator());
			treeWalk.addTree(new DirCacheIterator(dirCache));
			treeWalk.addTree(initialWorkingTreeIterator);
			initialWorkingTreeIterator.setDirCacheIterator(treeWalk, 1);
			Collection<TreeFilter> filters = new ArrayList<>(4);

			if(monitor != null) {
				if(estIndexSize == 0)
					estIndexSize = dirCache.getEntryCount();
				int total = Math.max(estIndexSize * 10 / 9,
						estWorkTreeSize * 10 / 9);
				monitor.beginTask(title, total);
				filters.add(new ProgressReportingFilter(monitor, total));
			}

			if(filter != null)
				filters.add(filter);
			filters.add(new SkipWorkTreeFilter(INDEX));
			indexDiffFilter = new IndexDiffFilter(INDEX, WORKDIR);
			filters.add(indexDiffFilter);
			treeWalk.setFilter(AndTreeFilter.create(filters));
			fileModes.clear();
			while(treeWalk.next()) {
				AbstractTreeIterator treeIterator = treeWalk.getTree(TREE
				);
				DirCacheIterator dirCacheIterator = treeWalk.getTree(INDEX
				);
				WorkingTreeIterator workingTreeIterator = treeWalk
						.getTree(WORKDIR);

				if(dirCacheIterator != null) {
					final DirCacheEntry dirCacheEntry = dirCacheIterator
							.getDirCacheEntry();
					if(dirCacheEntry != null) {
						int stage = dirCacheEntry.getStage();
						if(stage > 0) {
							String path = treeWalk.getPathString();
							addConflict(path, stage);
							continue;
						}
					}
				}

				if(treeIterator != null) {
					if(dirCacheIterator != null) {
						if(!treeIterator.idEqual(dirCacheIterator)
								|| treeIterator
								.getEntryRawMode() != dirCacheIterator
								.getEntryRawMode()) {
							if(!isEntryGitLink(treeIterator)
									|| !isEntryGitLink(dirCacheIterator)
									|| ignoreSubmoduleMode != IgnoreSubmoduleMode.ALL)
								changed.add(treeWalk.getPathString());
						}
					} else {
						if(!isEntryGitLink(treeIterator)
								|| ignoreSubmoduleMode != IgnoreSubmoduleMode.ALL)
							removed.add(treeWalk.getPathString());
						if(workingTreeIterator != null)
							untracked.add(treeWalk.getPathString());
					}
				} else {
					if(dirCacheIterator != null) {
						if(!isEntryGitLink(dirCacheIterator)
								|| ignoreSubmoduleMode != IgnoreSubmoduleMode.ALL)
							added.add(treeWalk.getPathString());
					} else {
						if(workingTreeIterator != null
								&& !workingTreeIterator.isEntryIgnored()) {
							untracked.add(treeWalk.getPathString());
						}
					}
				}

				if(dirCacheIterator != null) {
					if(workingTreeIterator == null) {
						boolean isGitLink = isEntryGitLink(dirCacheIterator);
						if(!isGitLink
								|| ignoreSubmoduleMode != IgnoreSubmoduleMode.ALL) {
							String path = treeWalk.getPathString();
							missing.add(path);
							if(isGitLink) {
								missingSubmodules.add(path);
							}
						}
					} else {
						if(workingTreeIterator.isModified(
								dirCacheIterator.getDirCacheEntry(), true,
								treeWalk.getObjectReader())) {
							if(!isEntryGitLink(dirCacheIterator)
									|| !isEntryGitLink(workingTreeIterator)
									|| (ignoreSubmoduleMode != IgnoreSubmoduleMode.ALL
									&& ignoreSubmoduleMode != IgnoreSubmoduleMode.DIRTY))
								modified.add(treeWalk.getPathString());
						}
					}
				}

				String path = treeWalk.getPathString();
				if(path != null) {
					for(int i = 0; i < treeWalk.getTreeCount(); i++) {
						recordFileMode(path, treeWalk.getFileMode(i));
					}
				}
			}
		}

		if(ignoreSubmoduleMode != IgnoreSubmoduleMode.ALL) {
			try(SubmoduleWalk smw = new SubmoduleWalk(repository)) {
				smw.setTree(new DirCacheIterator(dirCache));
				if(filter != null) {
					smw.setFilter(filter);
				}
				smw.setBuilderFactory(factory);
				while(smw.next()) {
					IgnoreSubmoduleMode localIgnoreSubmoduleMode = ignoreSubmoduleMode;
					try {
						if(localIgnoreSubmoduleMode == null)
							localIgnoreSubmoduleMode = smw.getModulesIgnore();
						if(IgnoreSubmoduleMode.ALL
								.equals(localIgnoreSubmoduleMode))
							continue;
					} catch(ConfigInvalidException e) {
						throw new IOException(MessageFormat.format(
								JGitText.get().invalidIgnoreParamSubmodule,
								smw.getPath()), e);
					}
					try(Repository subRepo = smw.getRepository()) {
						String subRepoPath = smw.getPath();
						if(subRepo != null) {
							ObjectId subHead = subRepo.resolve("HEAD");
							if(subHead != null
									&& !subHead.equals(smw.getObjectId())) {
								modified.add(subRepoPath);
								recordFileMode(subRepoPath, FileMode.GITLINK);
							} else if(localIgnoreSubmoduleMode != IgnoreSubmoduleMode.DIRTY) {
								IndexDiff smid = submoduleIndexDiffs
										.get(smw.getPath());
								if(smid == null) {
									smid = new IndexDiff(subRepo,
											smw.getObjectId(),
											wTreeIt.getWorkingTreeIterator(
													subRepo));
									submoduleIndexDiffs.put(subRepoPath, smid);
								}
								if(smid.diff(factory)) {
									if(localIgnoreSubmoduleMode == IgnoreSubmoduleMode.UNTRACKED
											&& smid.getAdded().isEmpty()
											&& smid.getChanged().isEmpty()
											&& smid.getConflicting().isEmpty()
											&& smid.getMissing().isEmpty()
											&& smid.getModified().isEmpty()
											&& smid.getRemoved().isEmpty()) {
										continue;
									}
									modified.add(subRepoPath);
									recordFileMode(subRepoPath,
											FileMode.GITLINK);
								}
							}
						} else if(missingSubmodules.remove(subRepoPath)) {
							File gitDir = new File(
									new File(repository.getDirectory(),
											Constants.MODULES),
									subRepoPath);
							if(!gitDir.isDirectory()) {
								File dir = SubmoduleWalk.getSubmoduleDirectory(
										repository, subRepoPath);
								if(dir.isDirectory() && !hasFiles(dir)) {
									missing.remove(subRepoPath);
								}
							}
						}
					}
				}
			}

		}

		if(monitor != null) {
			monitor.endTask();
		}

		ignored = indexDiffFilter.getIgnoredPaths();
		return !added.isEmpty() || !changed.isEmpty() || !removed.isEmpty()
				|| !missing.isEmpty() || !modified.isEmpty()
				|| !untracked.isEmpty();
	}

	private boolean hasFiles(File directory) {
		try(DirectoryStream<java.nio.file.Path> dir = Files
				.newDirectoryStream(directory.toPath())) {
			return dir.iterator().hasNext();
		} catch(DirectoryIteratorException | IOException e) {
			return false;
		}
	}

	private void recordFileMode(String path, FileMode mode) {
		Set<String> values = fileModes.get(mode);
		if(path != null) {
			if(values == null) {
				values = new HashSet<>();
				fileModes.put(mode, values);
			}
			values.add(path);
		}
	}

	private boolean isEntryGitLink(AbstractTreeIterator ti) {
		return ((ti != null) && (ti.getEntryRawMode() == FileMode.GITLINK
				.getBits()));
	}

	private void addConflict(String path, int stage) {
		StageState existingStageStates = conflicts.get(path);
		byte stageMask = 0;
		if(existingStageStates != null) {
			stageMask |= (byte) existingStageStates.getStageMask();
		}

		int shifts = stage - 1;
		stageMask |= (byte) (1 << shifts);
		StageState stageState = StageState.fromMask(stageMask);
		conflicts.put(path, stageState);
	}

	public Set<String> getAdded() {
		return added;
	}

	public Set<String> getChanged() {
		return changed;
	}

	public Set<String> getRemoved() {
		return removed;
	}

	public Set<String> getMissing() {
		return missing;
	}

	public Set<String> getModified() {
		return modified;
	}

	public Set<String> getUntracked() {
		return untracked;
	}

	public Set<String> getConflicting() {
		return conflicts.keySet();
	}

	public Set<String> getIgnoredNotInIndex() {
		return ignored;
	}
}
