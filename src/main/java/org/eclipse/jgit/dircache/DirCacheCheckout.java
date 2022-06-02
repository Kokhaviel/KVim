/*
 * Copyright (C) 2007, Dave Watson <dwatson@mimvista.com>
 * Copyright (C) 2008, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2008, Roger C. Soares <rogersoares@intelinet.com.br>
 * Copyright (C) 2006, Shawn O. Pearce <spearce@spearce.org>
 * Copyright (C) 2010, Chrisian Halstrick <christian.halstrick@sap.com>
 * Copyright (C) 2019, 2020, Andre Bossert <andre.bossert@siemens.com>
 * Copyright (C) 2017, 2022, Thomas Wolf <thomas.wolf@paranor.ch> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.dircache;

import org.eclipse.jgit.api.errors.CanceledException;
import org.eclipse.jgit.api.errors.FilterFailedException;
import org.eclipse.jgit.attributes.FilterCommandRegistry;
import org.eclipse.jgit.errors.CheckoutConflictException;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.IndexWriteException;
import org.eclipse.jgit.events.WorkingTreeModifiedEvent;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.lib.CoreConfig.AutoCRLF;
import org.eclipse.jgit.lib.CoreConfig.EolStreamType;
import org.eclipse.jgit.lib.CoreConfig.SymLinks;
import org.eclipse.jgit.treewalk.*;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.util.*;
import org.eclipse.jgit.util.FS.ExecutionResult;
import org.eclipse.jgit.util.io.EolStreamTypeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.*;

import static org.eclipse.jgit.treewalk.TreeWalk.OperationType.CHECKOUT_OP;

public class DirCacheCheckout {
	private static final Logger LOG = LoggerFactory.getLogger(DirCacheCheckout.class);

	private static final int MAX_EXCEPTION_TEXT_SIZE = 10 * 1024;

	public static class CheckoutMetadata {
		public final EolStreamType eolStreamType;
		public final String smudgeFilterCommand;

		public CheckoutMetadata(EolStreamType eolStreamType, String smudgeFilterCommand) {
			this.eolStreamType = eolStreamType;
			this.smudgeFilterCommand = smudgeFilterCommand;
		}

		static CheckoutMetadata EMPTY = new CheckoutMetadata(
				EolStreamType.DIRECT, null);
	}

	private final Repository repo;
	private final Map<String, CheckoutMetadata> updated = new LinkedHashMap<>();
	private final ArrayList<String> conflicts = new ArrayList<>();
	private ArrayList<String> removed = new ArrayList<>();
	private final ArrayList<String> kept = new ArrayList<>();
	private final ObjectId mergeCommitTree;
	private final DirCache dc;
	private DirCacheBuilder builder;
	private NameConflictTreeWalk walk;
	private final ObjectId headCommitTree;
	private final WorkingTreeIterator workingTree;
	private boolean failOnConflict = true;
	private boolean force = false;
	private final ArrayList<String> toBeDeleted = new ArrayList<>();
	private final boolean initialCheckout;
	private boolean performingCheckout;
	private ProgressMonitor monitor = NullProgressMonitor.INSTANCE;

	public Map<String, CheckoutMetadata> getUpdated() {
		return updated;
	}

	public List<String> getConflicts() {
		return conflicts;
	}

	public List<String> getToBeDeleted() {
		return toBeDeleted;
	}

	public List<String> getRemoved() {
		return removed;
	}

	public DirCacheCheckout(Repository repo, ObjectId headCommitTree, DirCache dc,
							ObjectId mergeCommitTree, WorkingTreeIterator workingTree)
			throws IOException {
		this.repo = repo;
		this.dc = dc;
		this.headCommitTree = headCommitTree;
		this.mergeCommitTree = mergeCommitTree;
		this.workingTree = workingTree;
		this.initialCheckout = !repo.isBare() && !repo.getIndexFile().exists();
	}

	public DirCacheCheckout(Repository repo, ObjectId headCommitTree,
							DirCache dc, ObjectId mergeCommitTree) throws IOException {
		this(repo, headCommitTree, dc, mergeCommitTree, new FileTreeIterator(repo));
	}

	public DirCacheCheckout(Repository repo, DirCache dc,
							ObjectId mergeCommitTree) throws IOException {
		this(repo, null, dc, mergeCommitTree, new FileTreeIterator(repo));
	}

	public void setProgressMonitor(ProgressMonitor monitor) {
		this.monitor = monitor != null ? monitor : NullProgressMonitor.INSTANCE;
	}

	public void preScanTwoTrees() throws IOException {
		removed.clear();
		updated.clear();
		conflicts.clear();
		walk = new NameConflictTreeWalk(repo);
		builder = dc.builder();

		walk.setHead(addTree(walk, headCommitTree));
		addTree(walk, mergeCommitTree);
		int dciPos = walk.addTree(new DirCacheBuildIterator(builder));
		walk.addTree(workingTree);
		workingTree.setDirCacheIterator(walk, dciPos);

		while(walk.next()) {
			processEntry(walk.getTree(0),
					walk.getTree(1),
					walk.getTree(2),
					walk.getTree(3));
			if(walk.isSubtree())
				walk.enterSubtree();
		}
	}

	public void prescanOneTree() throws IOException {
		removed.clear();
		updated.clear();
		conflicts.clear();

		builder = dc.builder();

		walk = new NameConflictTreeWalk(repo);
		walk.setHead(addTree(walk, mergeCommitTree));
		int dciPos = walk.addTree(new DirCacheBuildIterator(builder));
		walk.addTree(workingTree);
		workingTree.setDirCacheIterator(walk, dciPos);

		while(walk.next()) {
			processEntry(walk.getTree(0),
					walk.getTree(1),
					walk.getTree(2));
			if(walk.isSubtree())
				walk.enterSubtree();
		}
		conflicts.removeAll(removed);
	}

	private int addTree(TreeWalk tw, ObjectId id) throws IOException {
		if(id == null) {
			return tw.addTree(new EmptyTreeIterator());
		}
		return tw.addTree(id);
	}

	void processEntry(CanonicalTreeParser m, DirCacheBuildIterator i,
					  WorkingTreeIterator f) throws IOException {
		if(m != null) {
			checkValidPath(m);
			if(i == null) {
				if(f != null && !FileMode.TREE.equals(f.getEntryFileMode())
						&& !f.isEntryIgnored()) {
					if(failOnConflict) {
						conflicts.add(walk.getPathString());
					} else {
						update(m);
					}
				} else
					update(m);
			} else if(f == null || !m.idEqual(i)) {
				update(m);
			} else if(i.getDirCacheEntry() != null) {
				if(f.isModified(i.getDirCacheEntry(), true,
						this.walk.getObjectReader())
						|| i.getDirCacheEntry().getStage() != 0)
					update(m);
				else {
					DirCacheEntry entry = i.getDirCacheEntry();
					Instant mtime = entry.getLastModifiedInstant();
					if(mtime == null || mtime.equals(Instant.EPOCH)) {
						entry.setLastModified(f.getEntryLastModifiedInstant());
					}
					keep(i.getEntryPathString(), entry, f);
				}
			} else
				keep(i.getEntryPathString(), i.getDirCacheEntry(), f);
		} else {
			if(f != null) {
				if(walk.isDirectoryFileConflict()) {
					conflicts.add(walk.getPathString());
				} else {
					if(i != null) {
						remove(i.getEntryPathString());
						conflicts.remove(i.getEntryPathString());
					}

				}
			}
		}
	}

	public boolean checkout() throws IOException {
		try {
			return doCheckout();
		} catch(CanceledException ce) {
			throw new IOException(ce);
		} finally {
			try {
				dc.unlock();
			} finally {
				if(performingCheckout) {
					Set<String> touched = new HashSet<>(conflicts);
					touched.addAll(getUpdated().keySet());
					touched.addAll(kept);
					WorkingTreeModifiedEvent event = new WorkingTreeModifiedEvent(
							touched, getRemoved());
					if(!event.isEmpty()) {
						repo.fireEvent(event);
					}
				}
			}
		}
	}

	private boolean doCheckout() throws IOException, CanceledException {
		toBeDeleted.clear();
		try(ObjectReader objectReader = repo.getObjectDatabase().newReader()) {
			if(headCommitTree != null)
				preScanTwoTrees();
			else
				prescanOneTree();

			if(!conflicts.isEmpty()) {
				if(failOnConflict) {
					throw new CheckoutConflictException(conflicts.toArray(new String[0]));
				}
				cleanUpConflicts();
			}

			builder.finish();

			int numTotal = removed.size() + updated.size() + conflicts.size();
			monitor.beginTask(JGitText.get().checkingOutFiles, numTotal);

			performingCheckout = true;
			File file = null;
			String last = null;
			IntList nonDeleted = new IntList();
			for(int i = removed.size() - 1; i >= 0; i--) {
				String r = removed.get(i);
				file = new File(repo.getWorkTree(), r);
				if(!file.delete() && repo.getFS().exists(file)) {
					if(!repo.getFS().isDirectory(file)) {
						nonDeleted.add(i);
						toBeDeleted.add(r);
					}
				} else {
					if(last != null && !isSamePrefix(r, last))
						removeEmptyParents(new File(repo.getWorkTree(), last));
					last = r;
				}
				monitor.update(1);
				if(monitor.isCancelled()) {
					throw new CanceledException(MessageFormat.format(
							JGitText.get().operationCanceled,
							JGitText.get().checkingOutFiles));
				}
			}
			if(file != null) {
				removeEmptyParents(file);
			}
			removed = filterOut(removed, nonDeleted);
			Iterator<Map.Entry<String, CheckoutMetadata>> toUpdate = updated
					.entrySet().iterator();
			Map.Entry<String, CheckoutMetadata> e = null;
			try {
				while(toUpdate.hasNext()) {
					e = toUpdate.next();
					String path = e.getKey();
					CheckoutMetadata meta = e.getValue();
					DirCacheEntry entry = dc.getEntry(path);
					if(FileMode.GITLINK.equals(entry.getRawMode())) {
						checkoutGitlink(path, entry);
					} else {
						checkoutEntry(repo, entry, objectReader, false, meta);
					}
					e = null;

					monitor.update(1);
					if(monitor.isCancelled()) {
						throw new CanceledException(MessageFormat.format(
								JGitText.get().operationCanceled,
								JGitText.get().checkingOutFiles));
					}
				}
			} catch(Exception ex) {
				if(e != null) {
					toUpdate.remove();
				}
				while(toUpdate.hasNext()) {
					toUpdate.remove();
				}
				throw ex;
			}
			for(String conflict : conflicts) {
				int entryIdx = dc.findEntry(conflict);
				if(entryIdx >= 0) {
					while(entryIdx < dc.getEntryCount()) {
						DirCacheEntry entry = dc.getEntry(entryIdx);
						if(!entry.getPathString().equals(conflict)) {
							break;
						}
						if(entry.getStage() == DirCacheEntry.STAGE_3) {
							checkoutEntry(repo, entry, objectReader, false,
									null);
							break;
						}
						++entryIdx;
					}
				}

				monitor.update(1);
				if(monitor.isCancelled()) {
					throw new CanceledException(MessageFormat.format(
							JGitText.get().operationCanceled,
							JGitText.get().checkingOutFiles));
				}
			}
			monitor.endTask();

			if(!builder.commit())
				throw new IndexWriteException();
		}
		return toBeDeleted.isEmpty();
	}

	private void checkoutGitlink(String path, DirCacheEntry entry)
			throws IOException {
		File gitlinkDir = new File(repo.getWorkTree(), path);
		FileUtils.mkdirs(gitlinkDir, true);
		FS fs = repo.getFS();
		entry.setLastModified(fs.lastModifiedInstant(gitlinkDir));
	}

	private static ArrayList<String> filterOut(ArrayList<String> strings,
											   IntList indicesToRemove) {
		int n = indicesToRemove.size();
		if(n == strings.size()) {
			return new ArrayList<>(0);
		}
		switch(n) {
			case 0:
				return strings;
			case 1:
				strings.remove(indicesToRemove.get(0));
				return strings;
			default:
				int length = strings.size();
				ArrayList<String> result = new ArrayList<>(length - n);
				int j = n - 1;
				int idx = indicesToRemove.get(j);
				for(int i = 0; i < length; i++) {
					if(i == idx) {
						idx = (--j >= 0) ? indicesToRemove.get(j) : -1;
					} else {
						result.add(strings.get(i));
					}
				}
				return result;
		}
	}

	private static boolean isSamePrefix(String a, String b) {
		int as = a.lastIndexOf('/');
		int bs = b.lastIndexOf('/');
		return a.substring(0, as + 1).equals(b.substring(0, bs + 1));
	}

	private void removeEmptyParents(File f) {
		File parentFile = f.getParentFile();

		while(parentFile != null && !parentFile.equals(repo.getWorkTree())) {
			if(!parentFile.delete())
				break;
			parentFile = parentFile.getParentFile();
		}
	}

	private boolean equalIdAndMode(ObjectId id1, FileMode mode1, ObjectId id2,
								   FileMode mode2) {
		if(!mode1.equals(mode2))
			return false;
		return Objects.equals(id1, id2);
	}


	void processEntry(CanonicalTreeParser h, CanonicalTreeParser m,
					  DirCacheBuildIterator i, WorkingTreeIterator f) throws IOException {
		DirCacheEntry dce = i != null ? i.getDirCacheEntry() : null;

		String name = walk.getPathString();

		if(m != null)
			checkValidPath(m);

		if(i == null && m == null && h == null) {
			if(walk.isDirectoryFileConflict())
				conflict(name, null, null, null);

			return;
		}

		ObjectId iId = (i == null ? null : i.getEntryObjectId());
		ObjectId mId = (m == null ? null : m.getEntryObjectId());
		ObjectId hId = (h == null ? null : h.getEntryObjectId());
		FileMode iMode = (i == null ? null : i.getEntryFileMode());
		FileMode mMode = (m == null ? null : m.getEntryFileMode());
		FileMode hMode = (h == null ? null : h.getEntryFileMode());

		int ffMask = 0;
		if(h != null)
			ffMask = FileMode.TREE.equals(hMode) ? 0xD00 : 0xF00;
		if(i != null)
			ffMask |= FileMode.TREE.equals(iMode) ? 0x0D0 : 0x0F0;
		if(m != null)
			ffMask |= FileMode.TREE.equals(mMode) ? 0x00D : 0x00F;

		if(((ffMask & 0x222) != 0x000)
				&& (((ffMask & 0x00F) == 0x00D) || ((ffMask & 0x0F0) == 0x0D0) || ((ffMask & 0xF00) == 0xD00))) {

			switch(ffMask) {
				case 0xDDF:
					if(f != null && isModifiedSubtree_IndexWorkingtree(name)) {
						conflict(name, dce, h, m);
					} else {
						update(1, name, mId, mMode);
					}

					break;
				case 0xF0D:
					remove(name);
					break;
				case 0xDFF:
					if(equalIdAndMode(iId, iMode, mId, mMode))
						keep(name, dce, f);
					else
						conflict(name, dce, h, m);
					break;
				case 0xFDD:
					break;
				case 0xD0F:
					update(1, name, mId, mMode);
					break;
				case 0xDF0:
				case 0x0FD:
					conflict(name, dce, h, m);
					break;
				case 0xFDF:
					if(equalIdAndMode(hId, hMode, mId, mMode)) {
						if(isModifiedSubtree_IndexWorkingtree(name))
							conflict(name, dce, h, m);
						else
							update(1, name, mId, mMode);
					} else
						conflict(name, dce, h, m);
					break;
				case 0xFFD:
					if(equalIdAndMode(hId, hMode, iId, iMode))
						if(f != null
								&& f.isModified(dce, true,
								this.walk.getObjectReader()))
							conflict(name, dce, h, m);
						else
							remove(name);
					else
						conflict(name, dce, h, m);
					break;
				case 0x0DF:
					if(!isModifiedSubtree_IndexWorkingtree(name))
						update(1, name, mId, mMode);
					else
						conflict(name, dce, null, m);
					break;
				default:
					keep(name, dce, f);
			}
			return;
		}

		if((ffMask & 0x222) == 0) {
			if(f == null || FileMode.TREE.equals(f.getEntryFileMode())) {
				return;
			}
			if(!idEqual(h, m)) {
				conflict(name, null, null, null);
			}
			return;
		}

		if((ffMask == 0x00F) && f != null && FileMode.TREE.equals(f.getEntryFileMode())) {
			conflict(name, null, null, m);
			return;
		}

		if(i == null) {
			if(f != null && !f.isEntryIgnored()) {
				if(!FileMode.GITLINK.equals(mMode)) {
					if(mId == null
							|| !equalIdAndMode(mId, mMode,
							f.getEntryObjectId(), f.getEntryFileMode())) {
						conflict(name, null, h, m);
						return;
					}
				}
			}

			if(h == null)
				update(1, name, mId, mMode);
			else if(m == null)
				remove(name);
			else {
				if(equalIdAndMode(hId, hMode, mId, mMode)) {
					if(initialCheckout || force) {
						update(1, name, mId, mMode);
					} else {
						keep(name, null, f);
					}
				} else {
					conflict(name, null, h, m);
				}
			}
		} else {
			if(h == null) {
				if(m == null || !isModified_IndexTree(name, iId, iMode, mId, mMode, mergeCommitTree)) {
					if(m == null && walk.isDirectoryFileConflict()) {
						if(dce != null
								&& (f == null || f.isModified(dce, true,
								this.walk.getObjectReader())))
							conflict(name, dce, null, null);
						else
							remove(name);
					} else
						keep(name, dce, f);
				} else
					conflict(name, dce, null, m);
			} else if(m == null) {

				if(iMode == FileMode.GITLINK) {
					remove(name);
				} else {
					if(!isModified_IndexTree(name, iId, iMode, hId, hMode,
							headCommitTree)) {
						if(f != null
								&& f.isModified(dce, true,
								this.walk.getObjectReader())) {

							if(!FileMode.TREE.equals(f.getEntryFileMode())
									&& FileMode.TREE.equals(iMode)) {
								return;
							}
							conflict(name, dce, h, null);
						} else {
							remove(name);
						}
					} else {
						conflict(name, dce, h, null);
					}
				}
			} else {
				if(!equalIdAndMode(hId, hMode, mId, mMode)
						&& isModified_IndexTree(name, iId, iMode, hId, hMode, headCommitTree)
						&& isModified_IndexTree(name, iId, iMode, mId, mMode, mergeCommitTree))
					conflict(name, dce, h, m);
				else if(!isModified_IndexTree(name, iId, iMode, hId, hMode,
						headCommitTree)
						&& isModified_IndexTree(name, iId, iMode, mId, mMode,
						mergeCommitTree)) {

					if(dce != null && FileMode.GITLINK.equals(dce.getFileMode())) {
						update(1, name, mId, mMode);
					} else if(dce != null
							&& (f != null && f.isModified(dce, true, this.walk.getObjectReader()))) {
						conflict(name, dce, h, m);
					} else {
						update(1, name, mId, mMode);
					}
				} else {
					keep(name, dce, f);
				}
			}
		}
	}

	private static boolean idEqual(AbstractTreeIterator a,
								   AbstractTreeIterator b) {
		if(a == b) {
			return true;
		}
		if(a == null || b == null) {
			return false;
		}
		return a.getEntryObjectId().equals(b.getEntryObjectId());
	}

	private void conflict(String path, DirCacheEntry e, AbstractTreeIterator h, AbstractTreeIterator m) {
		conflicts.add(path);

		DirCacheEntry entry;
		if(e != null) {
			entry = new DirCacheEntry(e.getPathString(), DirCacheEntry.STAGE_1);
			entry.copyMetaData(e, true);
			builder.add(entry);
		}

		if(h != null && !FileMode.TREE.equals(h.getEntryFileMode())) {
			entry = new DirCacheEntry(h.getEntryPathString(), DirCacheEntry.STAGE_2);
			entry.setFileMode(h.getEntryFileMode());
			entry.setObjectId(h.getEntryObjectId());
			builder.add(entry);
		}

		if(m != null && !FileMode.TREE.equals(m.getEntryFileMode())) {
			entry = new DirCacheEntry(m.getEntryPathString(), DirCacheEntry.STAGE_3);
			entry.setFileMode(m.getEntryFileMode());
			entry.setObjectId(m.getEntryObjectId());
			builder.add(entry);
		}
	}

	private void keep(String path, DirCacheEntry e, WorkingTreeIterator f)
			throws IOException {
		if(e == null) {
			return;
		}
		if(!FileMode.TREE.equals(e.getFileMode())) {
			builder.add(e);
		}
		if(force) {
			if(f == null || f.isModified(e, true, walk.getObjectReader())) {
				kept.add(path);
				checkoutEntry(repo, e, walk.getObjectReader(), false,
						new CheckoutMetadata(walk.getEolStreamType(CHECKOUT_OP),
								walk.getFilterCommand(
										Constants.ATTR_FILTER_TYPE_SMUDGE)));
			}
		}
	}

	private void remove(String path) {
		removed.add(path);
	}

	private void update(CanonicalTreeParser tree) {
		update(0, tree.getEntryPathString(), tree.getEntryObjectId(),
				tree.getEntryFileMode());
	}

	private void update(int index, String path, ObjectId mId,
						FileMode mode) {
		if(!FileMode.TREE.equals(mode)) {
			updated.put(path, new CheckoutMetadata(
					walk.getCheckoutEolStreamType(index),
					walk.getSmudgeCommand(index)));

			DirCacheEntry entry = new DirCacheEntry(path, DirCacheEntry.STAGE_0);
			entry.setObjectId(mId);
			entry.setFileMode(mode);
			builder.add(entry);
		}
	}

	public void setFailOnConflict(boolean failOnConflict) {
		this.failOnConflict = failOnConflict;
	}

	public void setForce(boolean force) {
		this.force = force;
	}

	private void cleanUpConflicts() throws CheckoutConflictException {
		for(String c : conflicts) {
			File conflict = new File(repo.getWorkTree(), c);
			if(!conflict.delete())
				throw new CheckoutConflictException(MessageFormat.format(JGitText.get().cannotDeleteFile, c));
			removeEmptyParents(conflict);
		}
	}

	private boolean isModifiedSubtree_IndexWorkingtree(String path)
			throws IOException {
		try(NameConflictTreeWalk tw = new NameConflictTreeWalk(repo)) {
			int dciPos = tw.addTree(new DirCacheIterator(dc));
			FileTreeIterator fti = new FileTreeIterator(repo);
			tw.addTree(fti);
			fti.setDirCacheIterator(tw, dciPos);
			tw.setRecursive(true);
			tw.setFilter(PathFilter.create(path));
			DirCacheIterator dcIt;
			WorkingTreeIterator wtIt;
			while(tw.next()) {
				dcIt = tw.getTree(0);
				wtIt = tw.getTree(1);
				if(dcIt == null || wtIt == null)
					return true;
				if(wtIt.isModified(dcIt.getDirCacheEntry(), true, this.walk.getObjectReader())) {
					return true;
				}
			}
			return false;
		}
	}

	private boolean isModified_IndexTree(String path, ObjectId iId,
										 FileMode iMode, ObjectId tId, FileMode tMode, ObjectId rootTree)
			throws IOException {
		if(iMode != tMode) {
			return true;
		}
		if(FileMode.TREE.equals(iMode)
				&& (iId == null || ObjectId.zeroId().equals(iId))) {
			return isModifiedSubtree_IndexTree(path, rootTree);
		}
		return !equalIdAndMode(iId, iMode, tId, tMode);
	}

	private boolean isModifiedSubtree_IndexTree(String path, ObjectId tree)
			throws IOException {
		try(NameConflictTreeWalk tw = new NameConflictTreeWalk(repo)) {
			tw.addTree(new DirCacheIterator(dc));
			tw.addTree(tree);
			tw.setRecursive(true);
			tw.setFilter(PathFilter.create(path));
			while(tw.next()) {
				AbstractTreeIterator dcIt = tw.getTree(0
				);
				AbstractTreeIterator treeIt = tw.getTree(1
				);
				if(dcIt == null || treeIt == null)
					return true;
				if(dcIt.getEntryRawMode() != treeIt.getEntryRawMode())
					return true;
				if(!dcIt.getEntryObjectId().equals(treeIt.getEntryObjectId()))
					return true;
			}
			return false;
		}
	}

	public static void checkoutEntry(Repository repo, DirCacheEntry entry,
									 ObjectReader or, boolean deleteRecursive,
									 CheckoutMetadata checkoutMetadata) throws IOException {
		if(checkoutMetadata == null)
			checkoutMetadata = CheckoutMetadata.EMPTY;
		ObjectLoader ol = or.open(entry.getObjectId());
		File f = new File(repo.getWorkTree(), entry.getPathString());
		File parentDir = f.getParentFile();
		if(parentDir.isFile()) {
			FileUtils.delete(parentDir);
		}
		FileUtils.mkdirs(parentDir, true);
		FS fs = repo.getFS();
		WorkingTreeOptions opt = repo.getConfig().get(WorkingTreeOptions.KEY);
		if(entry.getFileMode() == FileMode.SYMLINK
				&& opt.getSymLinks() == SymLinks.TRUE) {
			byte[] bytes = ol.getBytes();
			String target = RawParseUtils.decode(bytes);
			if(deleteRecursive && f.isDirectory()) {
				FileUtils.delete(f, FileUtils.RECURSIVE);
			}
			fs.createSymLink(f, target);
			entry.setLength(bytes.length);
			entry.setLastModified(fs.lastModifiedInstant(f));
			return;
		}

		String name = f.getName();
		if(name.length() > 200) {
			name = name.substring(0, 200);
		}
		File tmpFile = File.createTempFile(
				"._" + name, null, parentDir);

		getContent(repo, entry.getPathString(), checkoutMetadata, ol, opt, Files.newOutputStream(tmpFile.toPath()));

		if(checkoutMetadata.eolStreamType == EolStreamType.DIRECT
				&& checkoutMetadata.smudgeFilterCommand == null) {
			entry.setLength(ol.getSize());
		} else {
			entry.setLength(tmpFile.length());
		}

		if(opt.isFileMode() && fs.supportsExecute()) {
			if(FileMode.EXECUTABLE_FILE.equals(entry.getRawMode())) {
				if(!fs.canExecute(tmpFile))
					fs.setExecute(tmpFile, true);
			} else {
				if(fs.canExecute(tmpFile))
					fs.setExecute(tmpFile, false);
			}
		}
		try {
			if(deleteRecursive && f.isDirectory()) {
				FileUtils.delete(f, FileUtils.RECURSIVE);
			}
			FileUtils.rename(tmpFile, f, StandardCopyOption.ATOMIC_MOVE);
		} catch(IOException e) {
			throw new IOException(
					MessageFormat.format(JGitText.get().renameFileFailed,
							tmpFile.getPath(), f.getPath()),
					e);
		} finally {
			if(tmpFile.exists()) {
				FileUtils.delete(tmpFile);
			}
		}
		entry.setLastModified(fs.lastModifiedInstant(f));
	}

	public static void getContent(Repository repo, String path,
								  CheckoutMetadata checkoutMetadata, ObjectLoader ol,
								  WorkingTreeOptions opt, OutputStream os)
			throws IOException {
		EolStreamType nonNullEolStreamType;
		if(checkoutMetadata.eolStreamType != null) {
			nonNullEolStreamType = checkoutMetadata.eolStreamType;
		} else if(opt.getAutoCRLF() == AutoCRLF.TRUE) {
			nonNullEolStreamType = EolStreamType.AUTO_CRLF;
		} else {
			nonNullEolStreamType = EolStreamType.DIRECT;
		}
		try(OutputStream channel = EolStreamTypeUtil.wrapOutputStream(
				os, nonNullEolStreamType)) {
			if(checkoutMetadata.smudgeFilterCommand != null) {
				if(FilterCommandRegistry
						.isRegistered(checkoutMetadata.smudgeFilterCommand)) {
					runBuiltinFilterCommand(repo, checkoutMetadata, ol,
							channel);
				} else {
					runExternalFilterCommand(repo, path, checkoutMetadata, ol,
							channel);
				}
			} else {
				ol.copyTo(channel);
			}
		}
	}

	private static void runExternalFilterCommand(Repository repo, String path,
												 CheckoutMetadata checkoutMetadata, ObjectLoader ol,
												 OutputStream channel) throws IOException {
		FS fs = repo.getFS();
		ProcessBuilder filterProcessBuilder = fs.runInShell(
				checkoutMetadata.smudgeFilterCommand, new String[0]);
		filterProcessBuilder.directory(repo.getWorkTree());
		filterProcessBuilder.environment().put(Constants.GIT_DIR_KEY,
				repo.getDirectory().getAbsolutePath());
		ExecutionResult result;
		int rc;
		try {
			result = fs.execute(filterProcessBuilder, ol.openStream());
			rc = result.getRc();
			if(rc == 0) {
				result.getStdout().writeTo(channel,
						NullProgressMonitor.INSTANCE);
			}
		} catch(IOException | InterruptedException e) {
			throw new IOException(new FilterFailedException(e,
					checkoutMetadata.smudgeFilterCommand,
					path));
		}
		if(rc != 0) {
			throw new IOException(new FilterFailedException(rc,
					checkoutMetadata.smudgeFilterCommand, path,
					result.getStdout().toByteArray(MAX_EXCEPTION_TEXT_SIZE),
					result.getStderr().toString(MAX_EXCEPTION_TEXT_SIZE)));
		}
	}

	private static void runBuiltinFilterCommand(Repository repo,
												CheckoutMetadata checkoutMetadata, ObjectLoader ol,
												OutputStream channel) throws IOException {
		boolean isMandatory = repo.getConfig().getBoolean(
				ConfigConstants.CONFIG_FILTER_SECTION,
				ConfigConstants.CONFIG_SECTION_LFS,
				ConfigConstants.CONFIG_KEY_REQUIRED, false);
		try {
			FilterCommandRegistry.createFilterCommand(
					checkoutMetadata.smudgeFilterCommand, repo, ol.openStream(),
					channel);
		} catch(IOException e) {
			LOG.error(JGitText.get().failedToDetermineFilterDefinition, e);
			if(!isMandatory) {
				ol.copyTo(channel);
			} else {
				throw e;
			}
		}
	}

	@SuppressWarnings("deprecation")
	private static void checkValidPath(CanonicalTreeParser t)
			throws InvalidPathException {
		ObjectChecker chk = new ObjectChecker()
				.setSafeForWindows(SystemReader.getInstance().isWindows())
				.setSafeForMacOS(SystemReader.getInstance().isMacOS());
		for(CanonicalTreeParser i = t; i != null; i = i.getParent())
			checkValidPathSegment(chk, i);
	}

	private static void checkValidPathSegment(ObjectChecker chk,
											  CanonicalTreeParser t) throws InvalidPathException {
		try {
			int ptr = t.getNameOffset();
			int end = ptr + t.getNameLength();
			chk.checkPathSegment(t.getEntryPathBuffer(), ptr, end);
		} catch(CorruptObjectException err) {
			String path = t.getEntryPathString();
			InvalidPathException i = new InvalidPathException(path);
			i.initCause(err);
			throw i;
		}
	}
}
