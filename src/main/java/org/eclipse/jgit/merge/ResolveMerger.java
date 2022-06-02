/*
 * Copyright (C) 2010, Christian Halstrick <christian.halstrick@sap.com>,
 * Copyright (C) 2010-2012, Matthias Sohn <matthias.sohn@sap.com>
 * Copyright (C) 2012, Research In Motion Limited
 * Copyright (C) 2017, Obeo (mathieu.cartaud@obeo.fr)
 * Copyright (C) 2018, 2022 Thomas Wolf <thomas.wolf@paranor.ch> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.merge;

import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.attributes.Attributes;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.DiffAlgorithm.SupportedAlgorithm;
import org.eclipse.jgit.diff.*;
import org.eclipse.jgit.dircache.*;
import org.eclipse.jgit.dircache.DirCacheCheckout.CheckoutMetadata;
import org.eclipse.jgit.errors.*;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.lib.CoreConfig.EolStreamType;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.storage.pack.PackConfig;
import org.eclipse.jgit.submodule.SubmoduleConflict;
import org.eclipse.jgit.treewalk.*;
import org.eclipse.jgit.treewalk.TreeWalk.OperationType;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.LfsFactory;
import org.eclipse.jgit.util.LfsFactory.LfsInputStream;
import org.eclipse.jgit.util.TemporaryBuffer;
import org.eclipse.jgit.util.io.EolStreamTypeUtil;

import java.io.*;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.jgit.diff.DiffAlgorithm.SupportedAlgorithm.HISTOGRAM;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_DIFF_SECTION;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_ALGORITHM;
import static org.eclipse.jgit.lib.Constants.OBJ_BLOB;

public class ResolveMerger extends ThreeWayMerger {

	public enum MergeFailureReason {
		DIRTY_INDEX,
		DIRTY_WORKTREE,
		COULD_NOT_DELETE
	}

	protected NameConflictTreeWalk tw;
	protected String[] commitNames;
	protected static final int T_BASE = 0;
	protected static final int T_OURS = 1;
	protected static final int T_THEIRS = 2;
	protected static final int T_INDEX = 3;
	protected static final int T_FILE = 4;
	protected DirCacheBuilder builder;
	protected ObjectId resultTree;
	protected List<String> unmergedPaths = new ArrayList<>();
	protected List<String> modifiedFiles = new LinkedList<>();
	protected Map<String, DirCacheEntry> toBeCheckedOut = new HashMap<>();
	protected List<String> toBeDeleted = new ArrayList<>();
	protected Map<String, MergeResult<? extends Sequence>> mergeResults = new HashMap<>();
	protected Map<String, MergeFailureReason> failingPaths = new HashMap<>();
	protected boolean enterSubtree;
	protected boolean inCore;
	protected boolean implicitDirCache;
	protected DirCache dircache;
	protected WorkingTreeIterator workingTreeIterator;
	protected MergeAlgorithm mergeAlgorithm;
	protected WorkingTreeOptions workingTreeOptions;
	private final int inCoreLimit;

	@NonNull
	private ContentMergeStrategy contentStrategy = ContentMergeStrategy.CONFLICT;

	private Map<String, CheckoutMetadata> checkoutMetadata;
	private Map<String, CheckoutMetadata> cleanupMetadata;

	private static MergeAlgorithm getMergeAlgorithm(Config config) {
		SupportedAlgorithm diffAlg = config.getEnum(
				CONFIG_DIFF_SECTION, null, CONFIG_KEY_ALGORITHM,
				HISTOGRAM);
		return new MergeAlgorithm(DiffAlgorithm.getAlgorithm(diffAlg));
	}

	private static int getInCoreLimit(Config config) {
		return config.getInt(
				ConfigConstants.CONFIG_MERGE_SECTION, ConfigConstants.CONFIG_KEY_IN_CORE_LIMIT, 10 << 20);
	}

	private static String[] defaultCommitNames() {
		return new String[] {"BASE", "OURS", "THEIRS"};
	}

	private static final Attributes NO_ATTRIBUTES = new Attributes();

	protected ResolveMerger(Repository local, boolean inCore) {
		super(local);
		Config config = local.getConfig();
		mergeAlgorithm = getMergeAlgorithm(config);
		inCoreLimit = getInCoreLimit(config);
		commitNames = defaultCommitNames();
		this.inCore = inCore;

		if(inCore) {
			implicitDirCache = false;
			dircache = DirCache.newInCore();
		} else {
			implicitDirCache = true;
			workingTreeOptions = local.getConfig().get(WorkingTreeOptions.KEY);
		}
	}

	@NonNull
	public ContentMergeStrategy getContentMergeStrategy() {
		return contentStrategy;
	}

	public void setContentMergeStrategy(ContentMergeStrategy strategy) {
		contentStrategy = strategy == null ? ContentMergeStrategy.CONFLICT : strategy;
	}

	@Override
	protected boolean mergeImpl() throws IOException {
		if(implicitDirCache) {
			dircache = nonNullRepo().lockDirCache();
		}
		if(!inCore) {
			checkoutMetadata = new HashMap<>();
			cleanupMetadata = new HashMap<>();
		}
		try {
			return mergeTrees(mergeBase(), sourceTrees[0], sourceTrees[1],
					false);
		} finally {
			checkoutMetadata = null;
			cleanupMetadata = null;
			if(implicitDirCache) {
				dircache.unlock();
			}
		}
	}

	private void checkout() throws NoWorkTreeException, IOException {
		for(int i = toBeDeleted.size() - 1; i >= 0; i--) {
			String fileName = toBeDeleted.get(i);
			File f = new File(nonNullRepo().getWorkTree(), fileName);
			if(!f.delete())
				if(!f.isDirectory())
					failingPaths.put(fileName,
							MergeFailureReason.COULD_NOT_DELETE);
			modifiedFiles.add(fileName);
		}
		for(Map.Entry<String, DirCacheEntry> entry : toBeCheckedOut
				.entrySet()) {
			DirCacheEntry cacheEntry = entry.getValue();
			if(cacheEntry.getFileMode() == FileMode.GITLINK) {
				new File(nonNullRepo().getWorkTree(), entry.getKey()).mkdirs();
			} else {
				DirCacheCheckout.checkoutEntry(db, cacheEntry, reader, false,
						checkoutMetadata.get(entry.getKey()));
				modifiedFiles.add(entry.getKey());
			}
		}
	}

	protected void cleanUp() throws NoWorkTreeException, IOException {
		if(inCore) {
			modifiedFiles.clear();
			return;
		}

		DirCache dc = nonNullRepo().readDirCache();
		Iterator<String> mpathsIt = modifiedFiles.iterator();
		while(mpathsIt.hasNext()) {
			String mpath = mpathsIt.next();
			DirCacheEntry entry = dc.getEntry(mpath);
			if(entry != null) {
				DirCacheCheckout.checkoutEntry(db, entry, reader, false,
						cleanupMetadata.get(mpath));
			}
			mpathsIt.remove();
		}
	}

	private DirCacheEntry add(byte[] path, CanonicalTreeParser p, int stage) {
		if(p != null && !p.getEntryFileMode().equals(FileMode.TREE)) {
			DirCacheEntry e = new DirCacheEntry(path, stage);
			e.setFileMode(p.getEntryFileMode());
			e.setObjectId(p.getEntryObjectId());
			e.setLastModified(Instant.EPOCH);
			e.setLength((long) 0);
			builder.add(e);
			return e;
		}
		return null;
	}

	private DirCacheEntry keep(DirCacheEntry e) {
		DirCacheEntry newEntry = new DirCacheEntry(e.getRawPath(),
				e.getStage());
		newEntry.setFileMode(e.getFileMode());
		newEntry.setObjectId(e.getObjectId());
		newEntry.setLastModified(e.getLastModifiedInstant());
		newEntry.setLength(e.getLength());
		builder.add(newEntry);
		return newEntry;
	}

	protected void addCheckoutMetadata(Map<String, CheckoutMetadata> map,
									   String path, Attributes attributes) {
		if(map != null) {
			EolStreamType eol = EolStreamTypeUtil.detectStreamType(
					OperationType.CHECKOUT_OP, workingTreeOptions,
					attributes);
			CheckoutMetadata data = new CheckoutMetadata(eol,
					tw.getSmudgeCommand(attributes));
			map.put(path, data);
		}
	}

	protected void addToCheckout(String path, DirCacheEntry entry, Attributes[] attributes) {
		toBeCheckedOut.put(path, entry);
		addCheckoutMetadata(cleanupMetadata, path, attributes[T_OURS]);
		addCheckoutMetadata(checkoutMetadata, path, attributes[T_THEIRS]);
	}

	protected void addDeletion(String path, boolean isFile, Attributes attributes) {
		toBeDeleted.add(path);
		if(isFile) {
			addCheckoutMetadata(cleanupMetadata, path, attributes);
		}
	}

	protected boolean processEntry(CanonicalTreeParser base,
								   CanonicalTreeParser ours, CanonicalTreeParser theirs,
								   DirCacheBuildIterator index, WorkingTreeIterator work,
								   boolean ignoreConflicts, Attributes[] attributes)
			throws IOException {
		enterSubtree = true;
		final int modeO = tw.getRawMode(T_OURS);
		final int modeT = tw.getRawMode(T_THEIRS);
		final int modeB = tw.getRawMode(T_BASE);
		boolean gitLinkMerging = isGitLink(modeO) || isGitLink(modeT)
				|| isGitLink(modeB);
		if(modeO == 0 && modeT == 0 && modeB == 0)
			return true;

		if(isIndexDirty())
			return false;

		DirCacheEntry ourDce = null;

		if(index == null || index.getDirCacheEntry() == null) {
			if(nonTree(modeO)) {
				ourDce = new DirCacheEntry(tw.getRawPath());
				ourDce.setObjectId(tw.getObjectId(T_OURS));
				ourDce.setFileMode(tw.getFileMode(T_OURS));
			}
		} else {
			ourDce = index.getDirCacheEntry();
		}

		if(nonTree(modeO) && nonTree(modeT) && tw.idEqual(T_OURS, T_THEIRS)) {
			if(modeO == modeT) {
				keep(ourDce);
				return true;
			}

			int newMode = mergeFileModes(modeB, modeO, modeT);
			if(newMode != FileMode.MISSING.getBits()) {
				if(newMode == modeO) {
					keep(ourDce);
				} else {
					if(isWorktreeDirty(work, ourDce)) {
						return false;
					}

					DirCacheEntry e = add(tw.getRawPath(), theirs,
							DirCacheEntry.STAGE_0);
					addToCheckout(tw.getPathString(), e, attributes);
				}
				return true;
			}
			if(!ignoreConflicts) {
				add(tw.getRawPath(), base, DirCacheEntry.STAGE_1);
				add(tw.getRawPath(), ours, DirCacheEntry.STAGE_2);
				add(tw.getRawPath(), theirs, DirCacheEntry.STAGE_3);
				unmergedPaths.add(tw.getPathString());
				mergeResults.put(tw.getPathString(),
						new MergeResult<>(Collections.emptyList()));
			}
			return true;
		}

		if(modeB == modeT && tw.idEqual(T_BASE, T_THEIRS)) {
			if(ourDce != null)
				keep(ourDce);
			return true;
		}

		if(modeB == modeO && tw.idEqual(T_BASE, T_OURS)) {
			if(isWorktreeDirty(work, ourDce))
				return false;
			if(nonTree(modeT)) {
				DirCacheEntry e = add(tw.getRawPath(), theirs,
						DirCacheEntry.STAGE_0);
				if(e != null) {
					addToCheckout(tw.getPathString(), e, attributes);
				}
				return true;
			}
			if(tw.getTreeCount() > T_FILE && tw.getRawMode(T_FILE) == 0) {
				return true;
			}
			if(modeT != 0 && modeT == modeB) {
				return true;
			}
			addDeletion(tw.getPathString(), nonTree(modeO), attributes[T_OURS]);
			return true;
		}

		if(tw.isSubtree()) {
			if(nonTree(modeO) != nonTree(modeT)) {
				if(ignoreConflicts) {
					enterSubtree = false;
					return true;
				}
				if(nonTree(modeB))
					add(tw.getRawPath(), base, DirCacheEntry.STAGE_1);
				if(nonTree(modeO))
					add(tw.getRawPath(), ours, DirCacheEntry.STAGE_2);
				if(nonTree(modeT))
					add(tw.getRawPath(), theirs, DirCacheEntry.STAGE_3);
				unmergedPaths.add(tw.getPathString());
				enterSubtree = false;
				return true;
			}

			if(!nonTree(modeO))
				return true;

		}

		if(nonTree(modeO) && nonTree(modeT)) {
			boolean worktreeDirty = isWorktreeDirty(work, ourDce);
			if(!attributes[T_OURS].canBeContentMerged() && worktreeDirty) {
				return false;
			}

			if(gitLinkMerging && ignoreConflicts) {
				add(tw.getRawPath(), ours, DirCacheEntry.STAGE_0);
				return true;
			} else if(gitLinkMerging) {
				add(tw.getRawPath(), base, DirCacheEntry.STAGE_1);
				add(tw.getRawPath(), ours, DirCacheEntry.STAGE_2);
				add(tw.getRawPath(), theirs, DirCacheEntry.STAGE_3);
				MergeResult<SubmoduleConflict> result = createGitLinksMergeResult(
						base, ours, theirs);
				result.setContainsConflicts(true);
				mergeResults.put(tw.getPathString(), result);
				unmergedPaths.add(tw.getPathString());
				return true;
			} else if(!attributes[T_OURS].canBeContentMerged()) {
				switch(getContentMergeStrategy()) {
					case OURS:
						keep(ourDce);
						return true;
					case THEIRS:
						DirCacheEntry theirEntry = add(tw.getRawPath(), theirs,
								DirCacheEntry.STAGE_0);
						addToCheckout(tw.getPathString(), theirEntry, attributes);
						return true;
					default:
						break;
				}
				add(tw.getRawPath(), base, DirCacheEntry.STAGE_1);
				add(tw.getRawPath(), ours, DirCacheEntry.STAGE_2);
				add(tw.getRawPath(), theirs, DirCacheEntry.STAGE_3);

				unmergedPaths.add(tw.getPathString());
				return true;
			}

			if(worktreeDirty) {
				return false;
			}

			MergeResult<RawText> result;
			try {
				result = contentMerge(base, ours, theirs, attributes,
						getContentMergeStrategy());
			} catch(BinaryBlobException e) {
				switch(getContentMergeStrategy()) {
					case OURS:
						keep(ourDce);
						return true;
					case THEIRS:
						DirCacheEntry theirEntry = add(tw.getRawPath(), theirs,
								DirCacheEntry.STAGE_0);
						addToCheckout(tw.getPathString(), theirEntry, attributes);
						return true;
					default:
						result = new MergeResult<>(Collections.emptyList());
						result.setContainsConflicts(true);
						break;
				}
			}
			if(ignoreConflicts) {
				result.setContainsConflicts(false);
			}
			updateIndex(base, ours, theirs, result, attributes[T_OURS]);
			String currentPath = tw.getPathString();
			if(result.containsConflicts() && !ignoreConflicts) {
				unmergedPaths.add(currentPath);
			}
			modifiedFiles.add(currentPath);
			addCheckoutMetadata(cleanupMetadata, currentPath,
					attributes[T_OURS]);
			addCheckoutMetadata(checkoutMetadata, currentPath,
					attributes[T_THEIRS]);
		} else if(modeO != modeT) {
			if(((modeO != 0 && !tw.idEqual(T_BASE, T_OURS)) || (modeT != 0 && !tw
					.idEqual(T_BASE, T_THEIRS)))) {
				if(gitLinkMerging && ignoreConflicts) {
					add(tw.getRawPath(), ours, DirCacheEntry.STAGE_0);
				} else if(gitLinkMerging) {
					add(tw.getRawPath(), base, DirCacheEntry.STAGE_1);
					add(tw.getRawPath(), ours, DirCacheEntry.STAGE_2);
					add(tw.getRawPath(), theirs, DirCacheEntry.STAGE_3);
					MergeResult<SubmoduleConflict> result = createGitLinksMergeResult(
							base, ours, theirs);
					result.setContainsConflicts(true);
					mergeResults.put(tw.getPathString(), result);
					unmergedPaths.add(tw.getPathString());
				} else {
					MergeResult<RawText> result;
					try {
						result = contentMerge(base, ours, theirs, attributes,
								ContentMergeStrategy.CONFLICT);
					} catch(BinaryBlobException e) {
						result = new MergeResult<>(Collections.emptyList());
						result.setContainsConflicts(true);
					}
					if(ignoreConflicts) {
						result.setContainsConflicts(false);
						updateIndex(base, ours, theirs, result,
								attributes[T_OURS]);
					} else {
						add(tw.getRawPath(), base, DirCacheEntry.STAGE_1
						);
						add(tw.getRawPath(), ours, DirCacheEntry.STAGE_2
						);
						DirCacheEntry e = add(tw.getRawPath(), theirs,
								DirCacheEntry.STAGE_3);

						if(modeO == 0) {
							if(isWorktreeDirty(work, ourDce)) {
								return false;
							}
							if(nonTree(modeT) && e != null) {
								addToCheckout(tw.getPathString(), e,
										attributes);
							}
						}

						unmergedPaths.add(tw.getPathString());
						mergeResults.put(tw.getPathString(), result);
					}
				}
			}
		}
		return true;
	}

	private static MergeResult<SubmoduleConflict> createGitLinksMergeResult(
			CanonicalTreeParser base, CanonicalTreeParser ours,
			CanonicalTreeParser theirs) {
		return new MergeResult<>(Arrays.asList(
				new SubmoduleConflict(
						base == null ? null : base.getEntryObjectId()),
				new SubmoduleConflict(
						ours == null ? null : ours.getEntryObjectId()),
				new SubmoduleConflict(
						theirs == null ? null : theirs.getEntryObjectId())));
	}

	private MergeResult<RawText> contentMerge(CanonicalTreeParser base,
											  CanonicalTreeParser ours, CanonicalTreeParser theirs,
											  Attributes[] attributes, ContentMergeStrategy strategy)
			throws BinaryBlobException, IOException {
		RawText baseText = base == null ? RawText.EMPTY_TEXT
				: getRawText(base.getEntryObjectId(), attributes[T_BASE]);
		RawText ourText = ours == null ? RawText.EMPTY_TEXT
				: getRawText(ours.getEntryObjectId(), attributes[T_OURS]);
		RawText theirsText = theirs == null ? RawText.EMPTY_TEXT
				: getRawText(theirs.getEntryObjectId(), attributes[T_THEIRS]);
		mergeAlgorithm.setContentMergeStrategy(strategy);
		return mergeAlgorithm.merge(RawTextComparator.DEFAULT, baseText,
				ourText, theirsText);
	}

	private boolean isIndexDirty() {
		if(inCore)
			return false;

		final int modeI = tw.getRawMode(T_INDEX);
		final int modeO = tw.getRawMode(T_OURS);

		final boolean isDirty = nonTree(modeI)
				&& !(modeO == modeI && tw.idEqual(T_INDEX, T_OURS));
		if(isDirty)
			failingPaths
					.put(tw.getPathString(), MergeFailureReason.DIRTY_INDEX);
		return isDirty;
	}

	private boolean isWorktreeDirty(WorkingTreeIterator work,
									DirCacheEntry ourDce) throws IOException {
		if(work == null)
			return false;

		final int modeF = tw.getRawMode(T_FILE);
		final int modeO = tw.getRawMode(T_OURS);

		boolean isDirty;
		if(ourDce != null)
			isDirty = work.isModified(ourDce, true, reader);
		else {
			isDirty = work.isModeDifferent(modeO);
			if(!isDirty && nonTree(modeF))
				isDirty = !tw.idEqual(T_FILE, T_OURS);
		}

		if(isDirty && modeF == FileMode.TYPE_TREE
				&& modeO == FileMode.TYPE_MISSING)
			isDirty = false;
		if(isDirty)
			failingPaths.put(tw.getPathString(),
					MergeFailureReason.DIRTY_WORKTREE);
		return isDirty;
	}

	private void updateIndex(CanonicalTreeParser base,
							 CanonicalTreeParser ours, CanonicalTreeParser theirs,
							 MergeResult<RawText> result, Attributes attributes)
			throws IOException {
		TemporaryBuffer rawMerged = null;
		try {
			rawMerged = doMerge(result);
			File mergedFile = inCore ? null
					: writeMergedFile(rawMerged, attributes);
			if(result.containsConflicts()) {
				add(tw.getRawPath(), base, DirCacheEntry.STAGE_1);
				add(tw.getRawPath(), ours, DirCacheEntry.STAGE_2);
				add(tw.getRawPath(), theirs, DirCacheEntry.STAGE_3);
				mergeResults.put(tw.getPathString(), result);
				return;
			}

			DirCacheEntry dce = new DirCacheEntry(tw.getPathString());

			int newMode = mergeFileModes(tw.getRawMode(0), tw.getRawMode(1),
					tw.getRawMode(2));
			dce.setFileMode(newMode == FileMode.MISSING.getBits()
					? FileMode.REGULAR_FILE : FileMode.fromBits(newMode));
			if(mergedFile != null) {
				dce.setLastModified(
						nonNullRepo().getFS().lastModifiedInstant(mergedFile));
				dce.setLength((int) mergedFile.length());
			}
			dce.setObjectId(insertMergeResult(rawMerged, attributes));
			builder.add(dce);
		} finally {
			if(rawMerged != null) {
				rawMerged.destroy();
			}
		}
	}

	private File writeMergedFile(TemporaryBuffer rawMerged,
								 Attributes attributes)
			throws IOException {
		File workTree = nonNullRepo().getWorkTree();
		FS fs = nonNullRepo().getFS();
		File of = new File(workTree, tw.getPathString());
		File parentFolder = of.getParentFile();
		if(!fs.exists(parentFolder)) {
			parentFolder.mkdirs();
		}
		EolStreamType streamType = EolStreamTypeUtil.detectStreamType(
				OperationType.CHECKOUT_OP, workingTreeOptions,
				attributes);
		try(OutputStream os = EolStreamTypeUtil.wrapOutputStream(
				new BufferedOutputStream(Files.newOutputStream(of.toPath())),
				streamType)) {
			rawMerged.writeTo(os, null);
		}
		return of;
	}

	private TemporaryBuffer doMerge(MergeResult<RawText> result)
			throws IOException {
		TemporaryBuffer.LocalFile buf = new TemporaryBuffer.LocalFile(
				db != null ? nonNullRepo().getDirectory() : null, inCoreLimit);
		boolean success = false;
		try {
			new MergeFormatter().formatMerge(buf, result,
					Arrays.asList(commitNames), UTF_8);
			buf.close();
			success = true;
		} finally {
			if(!success) {
				buf.destroy();
			}
		}
		return buf;
	}

	private ObjectId insertMergeResult(TemporaryBuffer buf,
									   Attributes attributes) throws IOException {
		InputStream in = buf.openInputStream();
		try(LfsInputStream is = LfsFactory.getInstance().applyCleanFilter(
				in,
				buf.length(), attributes.get(Constants.ATTR_MERGE))) {
			return getObjectInserter().insert(OBJ_BLOB, is.getLength(), is);
		}
	}

	private int mergeFileModes(int modeB, int modeO, int modeT) {
		if(modeO == modeT)
			return modeO;
		if(modeB == modeO)
			return (modeT == FileMode.MISSING.getBits()) ? modeO : modeT;
		if(modeB == modeT)
			return (modeO == FileMode.MISSING.getBits()) ? modeT : modeO;
		return FileMode.MISSING.getBits();
	}

	private RawText getRawText(ObjectId id,
							   Attributes attributes)
			throws IOException, BinaryBlobException {
		if(id.equals(ObjectId.zeroId()))
			return new RawText(new byte[] {});

		ObjectLoader loader = LfsFactory.getInstance().applySmudgeFilter(
				reader.open(id, OBJ_BLOB),
				attributes.get(Constants.ATTR_MERGE));
		int threshold = PackConfig.DEFAULT_BIG_FILE_THRESHOLD;
		return RawText.load(loader, threshold);
	}

	private static boolean nonTree(int mode) {
		return mode != 0 && !FileMode.TREE.equals(mode);
	}

	private static boolean isGitLink(int mode) {
		return FileMode.GITLINK.equals(mode);
	}

	@Override
	public ObjectId getResultTreeId() {
		return (resultTree == null) ? null : resultTree.toObjectId();
	}

	public void setCommitNames(String[] commitNames) {
		this.commitNames = commitNames;
	}

	public List<String> getUnmergedPaths() {
		return unmergedPaths;
	}

	public List<String> getModifiedFiles() {
		return modifiedFiles;
	}

	public Map<String, MergeResult<? extends Sequence>> getMergeResults() {
		return mergeResults;
	}

	public Map<String, MergeFailureReason> getFailingPaths() {
		return failingPaths.isEmpty() ? null : failingPaths;
	}

	public boolean failed() {
		return !failingPaths.isEmpty();
	}

	public void setWorkingTreeIterator(WorkingTreeIterator workingTreeIterator) {
		this.workingTreeIterator = workingTreeIterator;
	}

	protected boolean mergeTrees(AbstractTreeIterator baseTree,
								 RevTree headTree, RevTree mergeTree, boolean ignoreConflicts)
			throws IOException {

		builder = dircache.builder();
		DirCacheBuildIterator buildIt = new DirCacheBuildIterator(builder);

		tw = new NameConflictTreeWalk(db, reader);
		tw.addTree(baseTree);
		tw.setHead(tw.addTree(headTree));
		tw.addTree(mergeTree);
		int dciPos = tw.addTree(buildIt);
		if(workingTreeIterator != null) {
			tw.addTree(workingTreeIterator);
			workingTreeIterator.setDirCacheIterator(tw, dciPos);
		} else {
			tw.setFilter(TreeFilter.ANY_DIFF);
		}

		if(!mergeTreeWalk(tw, ignoreConflicts)) {
			return false;
		}

		if(!inCore) {
			checkout();

			if(!builder.commit()) {
				cleanUp();
				throw new IndexWriteException();
			}

		} else {
			builder.finish();
		}
		builder = null;

		if(getUnmergedPaths().isEmpty() && !failed()) {
			resultTree = dircache.writeTree(getObjectInserter());
			return true;
		}
		resultTree = null;
		return false;
	}

	protected boolean mergeTreeWalk(TreeWalk treeWalk, boolean ignoreConflicts)
			throws IOException {
		boolean hasWorkingTreeIterator = tw.getTreeCount() > T_FILE;
		boolean hasAttributeNodeProvider = treeWalk
				.getAttributesNodeProvider() != null;
		while(treeWalk.next()) {
			Attributes[] attributes = {NO_ATTRIBUTES, NO_ATTRIBUTES,
					NO_ATTRIBUTES};
			if(hasAttributeNodeProvider) {
				attributes[T_BASE] = treeWalk.getAttributes(T_BASE);
				attributes[T_OURS] = treeWalk.getAttributes(T_OURS);
				attributes[T_THEIRS] = treeWalk.getAttributes(T_THEIRS);
			}
			if(!processEntry(
					treeWalk.getTree(T_BASE),
					treeWalk.getTree(T_OURS),
					treeWalk.getTree(T_THEIRS),
					treeWalk.getTree(T_INDEX),
					hasWorkingTreeIterator ? treeWalk.getTree(T_FILE
					) : null,
					ignoreConflicts, attributes)) {
				cleanUp();
				return false;
			}
			if(treeWalk.isSubtree() && enterSubtree)
				treeWalk.enterSubtree();
		}
		return true;
	}
}
