/*
 * Copyright (C) 2012, Christian Halstrick <christian.halstrick@sap.com>
 * Copyright (C) 2011, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.internal.storage.file;

import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.errors.*;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.internal.storage.pack.*;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.lib.internal.WorkQueue;
import org.eclipse.jgit.revwalk.*;
import org.eclipse.jgit.storage.pack.PackConfig;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.eclipse.jgit.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.text.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.*;

import static org.eclipse.jgit.internal.storage.pack.PackExt.*;

public class GC {

	private static final Logger LOG = LoggerFactory.getLogger(GC.class);
	private static final String PRUNE_EXPIRE_DEFAULT = "2.weeks.ago";
	private static final String PRUNE_PACK_EXPIRE_DEFAULT = "1.hour.ago";
	private static final Pattern PATTERN_LOOSE_OBJECT = Pattern.compile("[\\da-fA-F]{38}");
	private static final String PACK_EXT = "." + PackExt.PACK.getExtension();
	private static final String BITMAP_EXT = "." + PackExt.BITMAP_INDEX.getExtension();
	private static final String INDEX_EXT = "." + PackExt.INDEX.getExtension();
	private static final String KEEP_EXT = "." + PackExt.KEEP.getExtension();
	private static final int DEFAULT_AUTOPACKLIMIT = 50;
	private static final int DEFAULT_AUTOLIMIT = 6700;
	private static volatile ExecutorService executor;

	private long expireAgeMillis = -1, packExpireAgeMillis = -1, lastRepackTime;
	private boolean automatic, background;
	private final FileRepository repo;
	private ProgressMonitor pm;
	private Date expire, packExpire;
	private PackConfig pconfig;
	private Collection<Ref> lastPackedRefs;

	public GC(FileRepository repo) {
		this.repo = repo;
		this.pconfig = new PackConfig(repo);
		this.pm = NullProgressMonitor.INSTANCE;
	}

	public CompletableFuture<Collection<Pack>> gc() throws IOException, ParseException {
		if(!background) return CompletableFuture.completedFuture(doGc());

		final GcLog gcLog = new GcLog(repo);
		if(!gcLog.lock()) return CompletableFuture.completedFuture(Collections.emptyList());

		Supplier<Collection<Pack>> gcTask = () -> {
			try {
				Collection<Pack> newPacks = doGc();
				if(automatic && tooManyLooseObjects()) {
					String message = JGitText.get().gcTooManyUnpruned;
					gcLog.write(message);
					gcLog.commit();
				}
				return newPacks;
			} catch(IOException | ParseException e) {
				try {
					gcLog.write(e.getMessage());
					StringWriter sw = new StringWriter();
					e.printStackTrace(new PrintWriter(sw));
					gcLog.write(sw.toString());
					gcLog.commit();
				} catch(IOException e2) {
					e2.addSuppressed(e);
					LOG.error(e2.getMessage(), e2);
				}
			} finally {
				gcLog.unlock();
			}
			return Collections.emptyList();
		};
		return CompletableFuture.supplyAsync(gcTask, executor());
	}

	private ExecutorService executor() {
		return (executor != null) ? executor : WorkQueue.getExecutor();
	}

	private Collection<Pack> doGc() throws IOException, ParseException {
		if(automatic && !needGc()) return Collections.emptyList();
		pm.start(6);
		packRefs();
		Collection<Pack> newPacks = repack();
		prune(Collections.emptySet());
		return newPacks;
	}

	private void loosen(ObjectDirectoryInserter inserter, ObjectReader reader, Pack pack, HashSet<ObjectId> existing)
			throws IOException {
		for(PackIndex.MutableEntry entry : pack) {
			ObjectId oid = entry.toObjectId();
			if(existing.contains(oid)) continue;
			existing.add(oid);
			ObjectLoader loader = reader.open(oid);
			inserter.insert(loader.getType(), loader.getSize(), loader.openStream(), true);
		}
	}

	private void deleteOldPacks(Collection<Pack> oldPacks, Collection<Pack> newPacks)
			throws ParseException, IOException {
		HashSet<ObjectId> ids = new HashSet<>();
		for(Pack pack : newPacks) for(PackIndex.MutableEntry entry : pack) ids.add(entry.toObjectId());

		ObjectReader reader = repo.newObjectReader();
		ObjectDirectory dir = repo.getObjectDatabase();
		ObjectDirectoryInserter inserter = dir.newInserter();
		boolean shouldLoosen = !"now".equals(getPruneExpireStr()) && getExpireDate() < Long.MAX_VALUE;
		prunePreserved();
		long packExpireDate = getPackExpireDate();
		oldPackLoop:
		for(Pack oldPack : oldPacks) {
			checkCancelled();
			String oldName = oldPack.getPackName();
			for(Pack newPack : newPacks) if(oldName.equals(newPack.getPackName())) continue oldPackLoop;

			if(!oldPack.shouldBeKept() && repo.getFS().lastModifiedInstant(oldPack.getPackFile())
					.toEpochMilli() < packExpireDate) {
				if(shouldLoosen) loosen(inserter, reader, oldPack, ids);
				oldPack.close();
				prunePack(oldPack.getPackFile());
			}
		}
		repo.getObjectDatabase().close();
	}

	private void removeOldPack(PackFile packFile, int deleteOptions) throws IOException {
		if(pconfig.isPreserveOldPacks()) {
			File oldPackDir = repo.getObjectDatabase().getPreservedDirectory();
			FileUtils.mkdir(oldPackDir, true);
			PackFile oldPackFile = packFile.createPreservedForDirectory(oldPackDir);
			FileUtils.rename(packFile, oldPackFile);
		} else {
			FileUtils.delete(packFile, deleteOptions);
		}
	}

	private void prunePreserved() {
		if(pconfig.isPrunePreserved()) {
			try {
				FileUtils.delete(repo.getObjectDatabase().getPreservedDirectory(),
						FileUtils.RECURSIVE | FileUtils.RETRY | FileUtils.SKIP_MISSING);
			} catch(IOException ignored) {
			}
		}
	}

	private void prunePack(PackFile packFile) {
		try {
			int deleteOptions = FileUtils.RETRY | FileUtils.SKIP_MISSING;
			removeOldPack(packFile.create(PackExt.PACK), deleteOptions);

			deleteOptions |= FileUtils.IGNORE_ERRORS;
			for(PackExt ext : PackExt.values())
				if(!PackExt.PACK.equals(ext)) removeOldPack(packFile.create(ext), deleteOptions);

		} catch(IOException ignored) {
		}
	}

	public void prunePacked() throws IOException {
		ObjectDirectory objdb = repo.getObjectDatabase();
		Collection<Pack> packs = objdb.getPacks();
		File objects = repo.getObjectsDirectory();
		String[] fanout = objects.list();

		if(fanout != null && fanout.length > 0) {
			pm.beginTask(JGitText.get().pruneLoosePackedObjects, fanout.length);
			try {
				for(String d : fanout) {
					checkCancelled();
					pm.update(1);
					if(d.length() != 2) continue;
					String[] entries = new File(objects, d).list();
					if(entries == null) continue;
					for(String e : entries) {
						checkCancelled();
						if(e.length() != Constants.OBJECT_ID_STRING_LENGTH - 2) continue;
						ObjectId id;
						try {
							id = ObjectId.fromString(d + e);
						} catch(IllegalArgumentException notAnObject) {
							continue;
						}
						boolean found = false;
						for(Pack p : packs) {
							checkCancelled();
							if(p.hasObject(id)) {
								found = true;
								break;
							}
						}
						if(found) FileUtils.delete(objdb.fileFor(id),
								FileUtils.RETRY | FileUtils.SKIP_MISSING | FileUtils.IGNORE_ERRORS);
					}
				}
			} finally {
				pm.endTask();
			}
		}
	}

	public void prune(Set<ObjectId> objectsToKeep) throws IOException, ParseException {
		long expireDate = getExpireDate();

		Map<ObjectId, File> deletionCandidates = new HashMap<>();
		Set<ObjectId> indexObjects = null;
		File objects = repo.getObjectsDirectory();
		String[] fanout = objects.list();
		if(fanout == null || fanout.length == 0) return;

		pm.beginTask(JGitText.get().pruneLooseUnreferencedObjects, fanout.length);
		try {
			for(String d : fanout) {
				checkCancelled();
				pm.update(1);
				if(d.length() != 2) continue;
				File dir = new File(objects, d);
				File[] entries = dir.listFiles();
				if(entries == null || entries.length == 0) {
					FileUtils.delete(dir, FileUtils.IGNORE_ERRORS);
					continue;
				}
				for(File f : entries) {
					checkCancelled();
					String fName = f.getName();
					if(fName.length() != Constants.OBJECT_ID_STRING_LENGTH - 2) continue;
					if(repo.getFS().lastModifiedInstant(f).toEpochMilli() >= expireDate) continue;

					try {
						ObjectId id = ObjectId.fromString(d + fName);
						if(objectsToKeep.contains(id)) continue;
						if(indexObjects == null) indexObjects = listNonHEADIndexObjects();
						if(indexObjects.contains(id)) continue;
						deletionCandidates.put(id, f);
					} catch(IllegalArgumentException ignored) {
					}
				}
			}
		} finally {
			pm.endTask();
		}

		if(deletionCandidates.isEmpty()) return;

		checkCancelled();
		Collection<Ref> newRefs;
		if(lastPackedRefs == null || lastPackedRefs.isEmpty()) newRefs = getAllRefs();
		else {
			Map<String, Ref> last = new HashMap<>();
			for(Ref r : lastPackedRefs) last.put(r.getName(), r);
			newRefs = new ArrayList<>();
			for(Ref r : getAllRefs()) {
				Ref old = last.get(r.getName());
				if(!equals(r, old)) newRefs.add(r);
			}
		}

		if(!newRefs.isEmpty()) {
			ObjectWalk w = new ObjectWalk(repo);
			try {
				for(Ref cr : newRefs) {
					checkCancelled();
					w.markStart(w.parseAny(cr.getObjectId()));
				}
				if(lastPackedRefs != null)
					for(Ref lpr : lastPackedRefs) w.markUninteresting(w.parseAny(lpr.getObjectId()));
				removeReferenced(deletionCandidates, w);
			} finally {
				w.dispose();
			}
		}

		if(deletionCandidates.isEmpty()) return;
		ObjectWalk w = new ObjectWalk(repo);
		try {
			for(Ref ar : getAllRefs())
				for(ObjectId id : listRefLogObjects(ar, lastRepackTime)) {
					checkCancelled();
					w.markStart(w.parseAny(id));
				}
			if(lastPackedRefs != null)
				for(Ref lpr : lastPackedRefs) {
					checkCancelled();
					w.markUninteresting(w.parseAny(lpr.getObjectId()));
				}
			removeReferenced(deletionCandidates, w);
		} finally {
			w.dispose();
		}

		if(deletionCandidates.isEmpty()) return;
		checkCancelled();

		Set<File> touchedFanout = new HashSet<>();
		for(File f : deletionCandidates.values()) {
			if(f.lastModified() < expireDate) {
				f.delete();
				touchedFanout.add(f.getParentFile());
			}
		}
		for(File f : touchedFanout) FileUtils.delete(f, FileUtils.EMPTY_DIRECTORIES_ONLY | FileUtils.IGNORE_ERRORS);
		repo.getObjectDatabase().close();
	}

	private long getExpireDate() throws ParseException {
		long expireDate = Long.MAX_VALUE;

		if(expire == null && expireAgeMillis == -1) {
			String pruneExpireStr = getPruneExpireStr();
			if(pruneExpireStr == null) pruneExpireStr = PRUNE_EXPIRE_DEFAULT;
			expire = GitDateParser.parse(pruneExpireStr, null, SystemReader.getInstance().getLocale());
			expireAgeMillis = -1;
		}
		if(expire != null) expireDate = expire.getTime();
		if(expireAgeMillis != -1) expireDate = System.currentTimeMillis() - expireAgeMillis;
		return expireDate;
	}

	private String getPruneExpireStr() {
		return repo.getConfig().getString(ConfigConstants.CONFIG_GC_SECTION, null,
				ConfigConstants.CONFIG_KEY_PRUNEEXPIRE);
	}

	private long getPackExpireDate() throws ParseException {
		long packExpireDate = Long.MAX_VALUE;

		if(packExpire == null && packExpireAgeMillis == -1) {
			String prunePackExpireStr = repo.getConfig().getString(ConfigConstants.CONFIG_GC_SECTION, null,
					ConfigConstants.CONFIG_KEY_PRUNEPACKEXPIRE);
			if(prunePackExpireStr == null) prunePackExpireStr = PRUNE_PACK_EXPIRE_DEFAULT;
			packExpire = GitDateParser.parse(prunePackExpireStr, null, SystemReader.getInstance().getLocale());
			packExpireAgeMillis = -1;
		}
		if(packExpire != null) packExpireDate = packExpire.getTime();
		if(packExpireAgeMillis != -1) packExpireDate = System.currentTimeMillis() - packExpireAgeMillis;
		return packExpireDate;
	}

	private void removeReferenced(Map<ObjectId, File> id2File, ObjectWalk w) throws IOException {
		RevObject ro = w.next();
		while(ro != null) {
			checkCancelled();
			if(id2File.remove(ro.getId()) != null && id2File.isEmpty()) return;
			ro = w.next();
		}
		ro = w.nextObject();
		while(ro != null) {
			checkCancelled();
			if(id2File.remove(ro.getId()) != null && id2File.isEmpty()) return;
			ro = w.nextObject();
		}
	}

	private static boolean equals(Ref r1, Ref r2) {
		if(r1 == null || r2 == null) return false;
		if(r1.isSymbolic()) return r2.isSymbolic() && r1.getTarget().getName().equals(r2.getTarget().getName());
		return !r2.isSymbolic() && Objects.equals(r1.getObjectId(), r2.getObjectId());
	}

	public void packRefs() throws IOException {
		RefDatabase refDb = repo.getRefDatabase();
		if(refDb instanceof FileReftableDatabase) {
			pm.beginTask(JGitText.get().packRefs, 1);
			try {
				((FileReftableDatabase) refDb).compactFully();
			} finally {
				pm.endTask();
			}
			return;
		}

		Collection<Ref> refs = refDb.getRefsByPrefix(Constants.R_REFS);
		List<String> refsToBePacked = new ArrayList<>(refs.size());
		pm.beginTask(JGitText.get().packRefs, refs.size());
		try {
			for(Ref ref : refs) {
				checkCancelled();
				if(!ref.isSymbolic() && ref.getStorage().isLoose()) refsToBePacked.add(ref.getName());
				pm.update(1);
			}
			((RefDirectory) repo.getRefDatabase()).pack(refsToBePacked);
		} finally {
			pm.endTask();
		}
	}

	public Collection<Pack> repack() throws IOException {
		Collection<Pack> toBeDeleted = repo.getObjectDatabase().getPacks();

		long time = System.currentTimeMillis();
		Collection<Ref> refsBefore = getAllRefs();

		Set<ObjectId> allHeadsAndTags = new HashSet<>();
		Set<ObjectId> allHeads = new HashSet<>();
		Set<ObjectId> allTags = new HashSet<>();
		Set<ObjectId> nonHeads = new HashSet<>();
		Set<ObjectId> tagTargets = new HashSet<>();
		Set<ObjectId> indexObjects = listNonHEADIndexObjects();

		for(Ref ref : refsBefore) {
			checkCancelled();
			nonHeads.addAll(listRefLogObjects(ref, 0));
			if(ref.isSymbolic() || ref.getObjectId() == null) continue;

			if(isHead(ref)) allHeads.add(ref.getObjectId());
			else if(isTag(ref)) allTags.add(ref.getObjectId());
			else nonHeads.add(ref.getObjectId());

			if(ref.getPeeledObjectId() != null) tagTargets.add(ref.getPeeledObjectId());

		}

		List<ObjectIdSet> excluded = new LinkedList<>();
		for(Pack p : repo.getObjectDatabase().getPacks()) {
			checkCancelled();
			if(p.shouldBeKept()) excluded.add(p.getIndex());
		}

		allTags.removeAll(allHeads);
		allHeadsAndTags.addAll(allHeads);
		allHeadsAndTags.addAll(allTags);

		tagTargets.addAll(allHeadsAndTags);
		nonHeads.addAll(indexObjects);

		if(pconfig.getSinglePack()) {
			allHeadsAndTags.addAll(nonHeads);
			nonHeads.clear();
		}

		List<Pack> ret = new ArrayList<>(2);
		Pack heads;
		if(!allHeadsAndTags.isEmpty()) {
			heads = writePack(allHeadsAndTags, PackWriter.NONE, allTags, tagTargets, excluded);
			if(heads != null) {
				ret.add(heads);
				excluded.add(0, heads.getIndex());
			}
		}
		if(!nonHeads.isEmpty()) {
			Pack rest = writePack(nonHeads, allHeadsAndTags, PackWriter.NONE, tagTargets, excluded);
			if(rest != null) ret.add(rest);
		}
		try {
			deleteOldPacks(toBeDeleted, ret);
		} catch(ParseException e) {
			throw new IOException(e);
		}
		prunePacked();
		if(repo.getRefDatabase() instanceof RefDirectory) deleteEmptyRefsFolders();

		deleteOrphans();
		deleteTempPacksIdx();

		lastPackedRefs = refsBefore;
		lastRepackTime = time;
		return ret;
	}

	private static boolean isHead(Ref ref) {
		return ref.getName().startsWith(Constants.R_HEADS);
	}

	private static boolean isTag(Ref ref) {
		return ref.getName().startsWith(Constants.R_TAGS);
	}

	private void deleteEmptyRefsFolders() throws IOException {
		Path refs = repo.getDirectory().toPath().resolve(Constants.R_REFS);
		Instant threshold = Instant.now().minus(30, ChronoUnit.SECONDS);
		try(Stream<Path> entries = Files.list(refs).filter(Files::isDirectory)) {
			Iterator<Path> iterator = entries.iterator();
			while(iterator.hasNext()) {
				try(Stream<Path> s = Files.list(iterator.next())) {
					s.filter(path -> canBeSafelyDeleted(path, threshold)).forEach(this::deleteDir);
				}
			}
		}
	}

	private boolean canBeSafelyDeleted(Path path, Instant threshold) {
		try {
			return Files.getLastModifiedTime(path).toInstant().isBefore(threshold);
		} catch(IOException e) {
			LOG.warn(MessageFormat.format(JGitText.get().cannotAccessLastModifiedForSafeDeletion, path), e);
			return false;
		}
	}

	private void deleteDir(Path dir) {
		try(Stream<Path> dirs = Files.walk(dir)) {
			dirs.filter(this::isDirectory).sorted(Comparator.reverseOrder()).forEach(this::delete);
		} catch(IOException e) {
			LOG.error(e.getMessage(), e);
		}
	}

	private boolean isDirectory(Path p) {
		return p.toFile().isDirectory();
	}

	private void delete(Path d) {
		try {
			Files.delete(d);
		} catch(DirectoryNotEmptyException ignored) {
		} catch(IOException e) {
			LOG.error(MessageFormat.format(JGitText.get().cannotDeleteFile, d), e);
		}
	}

	private void deleteOrphans() {
		Path packDir = repo.getObjectDatabase().getPackDirectory().toPath();
		List<String> fileNames;
		try(Stream<Path> files = Files.list(packDir)) {
			fileNames = files.map(path -> path.getFileName().toString())
					.filter(name -> (name.endsWith(PACK_EXT) || name.endsWith(BITMAP_EXT)
							|| name.endsWith(INDEX_EXT) || name.endsWith(KEEP_EXT)))
					.sorted(Collections.reverseOrder()).collect(Collectors.toList());
		} catch(IOException e) {
			LOG.error(e.getMessage(), e);
			return;
		}

		String latestId = null;
		for(String n : fileNames) {
			PackFile pf = new PackFile(packDir.toFile(), n);
			PackExt ext = pf.getPackExt();
			if(ext.equals(PACK) || ext.equals(KEEP)) latestId = pf.getId();

			if(!pf.getId().equals(latestId)) {
				try {
					FileUtils.delete(pf, FileUtils.RETRY | FileUtils.SKIP_MISSING);
					LOG.warn(JGitText.get().deletedOrphanInPackDir, pf);
				} catch(IOException e) {
					LOG.error(e.getMessage(), e);
				}
			}
		}
	}

	private void deleteTempPacksIdx() {
		Path packDir = repo.getObjectDatabase().getPackDirectory().toPath();
		Instant threshold = Instant.now().minus(1, ChronoUnit.DAYS);
		if(!Files.exists(packDir)) return;

		try(DirectoryStream<Path> stream = Files.newDirectoryStream(packDir, "gc_*_tmp")) {
			stream.forEach(t -> {
				try {
					Instant lastModified = Files.getLastModifiedTime(t).toInstant();
					if(lastModified.isBefore(threshold)) Files.deleteIfExists(t);

				} catch(IOException e) {
					LOG.error(e.getMessage(), e);
				}
			});
		} catch(IOException e) {
			LOG.error(e.getMessage(), e);
		}
	}

	private Set<ObjectId> listRefLogObjects(Ref ref, long minTime) throws IOException {
		ReflogReader reflogReader = repo.getReflogReader(ref.getName());
		if(reflogReader == null) return Collections.emptySet();

		List<ReflogEntry> rlEntries = reflogReader.getReverseEntries();
		if(rlEntries == null || rlEntries.isEmpty()) return Collections.emptySet();
		Set<ObjectId> ret = new HashSet<>();
		for(ReflogEntry e : rlEntries) {
			if(e.getWho().getWhen().getTime() < minTime) break;
			ObjectId newId = e.getNewId();
			if(newId != null && !ObjectId.zeroId().equals(newId)) ret.add(newId);
			ObjectId oldId = e.getOldId();
			if(oldId != null && !ObjectId.zeroId().equals(oldId)) ret.add(oldId);
		}
		return ret;
	}

	private Collection<Ref> getAllRefs() throws IOException {
		RefDatabase refdb = repo.getRefDatabase();
		Collection<Ref> refs = refdb.getRefs();
		List<Ref> addl = refdb.getAdditionalRefs();
		if(!addl.isEmpty()) {
			List<Ref> all = new ArrayList<>(refs.size() + addl.size());
			all.addAll(refs);
			for(Ref r : addl) {
				checkCancelled();
				if(r.getName().startsWith(Constants.R_REFS)) {
					all.add(r);
				}
			}
			return all;
		}
		return refs;
	}

	private Set<ObjectId> listNonHEADIndexObjects()
			throws IOException {
		if(repo.isBare()) {
			return Collections.emptySet();
		}
		try(TreeWalk treeWalk = new TreeWalk(repo)) {
			treeWalk.addTree(new DirCacheIterator(repo.readDirCache()));
			ObjectId headID = repo.resolve(Constants.HEAD);
			if(headID != null) {
				try(RevWalk revWalk = new RevWalk(repo)) {
					treeWalk.addTree(revWalk.parseTree(headID));
				}
			}

			treeWalk.setFilter(TreeFilter.ANY_DIFF);
			treeWalk.setRecursive(true);
			Set<ObjectId> ret = new HashSet<>();

			while(treeWalk.next()) {
				checkCancelled();
				ObjectId objectId = treeWalk.getObjectId(0);
				switch(treeWalk.getRawMode(0) & FileMode.TYPE_MASK) {
					case FileMode.TYPE_MISSING:
					case FileMode.TYPE_GITLINK:
						continue;
					case FileMode.TYPE_TREE:
					case FileMode.TYPE_FILE:
					case FileMode.TYPE_SYMLINK:
						ret.add(objectId);
						continue;
					default:
						throw new IOException(MessageFormat.format(
								JGitText.get().corruptObjectInvalidMode3,
								String.format("%o",
										treeWalk.getRawMode(0)),
								(objectId == null) ? "null" : objectId.name(),
								treeWalk.getPathString(),
								repo.getIndexFile()));
				}
			}
			return ret;
		}
	}

	private Pack writePack(@NonNull Set<? extends ObjectId> want,
						   @NonNull Set<? extends ObjectId> have, @NonNull Set<ObjectId> tags,
						   Set<ObjectId> tagTargets, List<ObjectIdSet> excludeObjects)
			throws IOException {
		checkCancelled();
		File tmpPack = null;
		Map<PackExt, File> tmpExts = new TreeMap<>((o1, o2) -> {
			if(o1 == o2) {
				return 0;
			}
			if(o1 == PackExt.INDEX) {
				return 1;
			}
			if(o2 == PackExt.INDEX) {
				return -1;
			}
			return Integer.signum(o1.hashCode() - o2.hashCode());
		});
		try(PackWriter pw = new PackWriter(pconfig, repo.newObjectReader())) {
			pw.setDeltaBaseAsOffset(true);
			pw.setReuseDeltaCommits(false);
			if(tagTargets != null) {
				pw.setTagTargets(tagTargets);
			}
			if(excludeObjects != null)
				for(ObjectIdSet idx : excludeObjects)
					pw.excludeObjects(idx);
			pw.preparePack(pm, want, have, PackWriter.NONE, tags);
			if(pw.getObjectCount() == 0)
				return null;
			checkCancelled();

			ObjectId id = pw.computeName();
			File packdir = repo.getObjectDatabase().getPackDirectory();
			packdir.mkdirs();
			tmpPack = File.createTempFile("gc_", ".pack_tmp", packdir);
			final String tmpBase = tmpPack.getName()
					.substring(0, tmpPack.getName().lastIndexOf('.'));
			File tmpIdx = new File(packdir, tmpBase + ".idx_tmp");
			tmpExts.put(INDEX, tmpIdx);

			if(!tmpIdx.createNewFile())
				throw new IOException(MessageFormat.format(JGitText.get().cannotCreateIndexfile, tmpIdx.getPath()));

			try(FileOutputStream fos = new FileOutputStream(tmpPack);
				FileChannel channel = fos.getChannel();
				OutputStream channelStream = Channels
						.newOutputStream(channel)) {
				pw.writePack(pm, pm, channelStream);
				channel.force(true);
			}

			try(FileOutputStream fos = new FileOutputStream(tmpIdx);
				FileChannel idxChannel = fos.getChannel();
				OutputStream idxStream = Channels
						.newOutputStream(idxChannel)) {
				pw.writeIndex(idxStream);
				idxChannel.force(true);
			}

			if(pw.prepareBitmapIndex(pm)) {
				File tmpBitmapIdx = new File(packdir, tmpBase + ".bitmap_tmp");
				tmpExts.put(BITMAP_INDEX, tmpBitmapIdx);

				if(!tmpBitmapIdx.createNewFile())
					throw new IOException(MessageFormat.format(
							JGitText.get().cannotCreateIndexfile,
							tmpBitmapIdx.getPath()));

				try(FileOutputStream fos = new FileOutputStream(tmpBitmapIdx);
					FileChannel idxChannel = fos.getChannel();
					OutputStream idxStream = Channels
							.newOutputStream(idxChannel)) {
					pw.writeBitmapIndex(idxStream);
					idxChannel.force(true);
				}
			}

			File packDir = repo.getObjectDatabase().getPackDirectory();
			PackFile realPack = new PackFile(packDir, id, PackExt.PACK);

			repo.getObjectDatabase().closeAllPackHandles(realPack);
			tmpPack.setReadOnly();

			FileUtils.rename(tmpPack, realPack, StandardCopyOption.ATOMIC_MOVE);
			for(Map.Entry<PackExt, File> tmpEntry : tmpExts.entrySet()) {
				File tmpExt = tmpEntry.getValue();
				tmpExt.setReadOnly();

				PackFile realExt = new PackFile(packDir, id, tmpEntry.getKey());
				try {
					FileUtils.rename(tmpExt, realExt,
							StandardCopyOption.ATOMIC_MOVE);
				} catch(IOException e) {
					IOException ioException = e;
					File newExt = new File(realExt.getParentFile(),
							realExt.getName() + ".new");
					try {
						FileUtils.rename(tmpExt, newExt,
								StandardCopyOption.ATOMIC_MOVE);
					} catch(IOException e2) {
						newExt = tmpExt;
						ioException = e2;
					}
					throw new IOException(MessageFormat.format(
							JGitText.get().panicCantRenameIndexFile, newExt,
							realExt), ioException);
				}
			}
			boolean interrupted = false;
			try {
				FileSnapshot snapshot = FileSnapshot.save(realPack);
				if(pconfig.doWaitPreventRacyPack(snapshot.size())) {
					snapshot.waitUntilNotRacy();
				}
			} catch(InterruptedException e) {
				interrupted = true;
			}
			try {
				return repo.getObjectDatabase().openPack(realPack);
			} finally {
				if(interrupted) {
					Thread.currentThread().interrupt();
				}
			}
		} finally {
			if(tmpPack != null && tmpPack.exists())
				tmpPack.delete();
			for(File tmpExt : tmpExts.values()) {
				if(tmpExt.exists())
					tmpExt.delete();
			}
		}
	}

	private void checkCancelled() throws CancelledException {
		if(pm.isCancelled() || Thread.currentThread().isInterrupted()) {
			throw new CancelledException(JGitText.get().operationCanceled);
		}
	}

	public GC setProgressMonitor(ProgressMonitor pm) {
		this.pm = (pm == null) ? NullProgressMonitor.INSTANCE : pm;
		return this;
	}

	public void setPackConfig(@NonNull PackConfig pconfig) {
		this.pconfig = pconfig;
	}

	public void setAuto(boolean auto) {
		this.automatic = auto;
	}

	void setBackground(boolean background) {
		this.background = background;
	}

	private boolean needGc() {
		if(!tooManyPacks()) {
			return tooManyLooseObjects();
		}
		return true;
	}

	boolean tooManyPacks() {
		int autopacklimit = repo.getConfig().getInt(
				ConfigConstants.CONFIG_GC_SECTION,
				ConfigConstants.CONFIG_KEY_AUTOPACKLIMIT,
				DEFAULT_AUTOPACKLIMIT);
		if(autopacklimit <= 0) {
			return false;
		}
		return repo.getObjectDatabase().getPacks().size() > (autopacklimit + 1);
	}

	boolean tooManyLooseObjects() {
		int auto = getLooseObjectLimit();
		if(auto <= 0) {
			return false;
		}
		int n = 0;
		int threshold = (auto + 255) / 256;
		Path dir = repo.getObjectsDirectory().toPath().resolve("17");
		if(!dir.toFile().exists()) {
			return false;
		}
		try(DirectoryStream<Path> stream = Files.newDirectoryStream(dir, file -> {
			Path fileName = file.getFileName();
			return file.toFile().isFile() && fileName != null
					&& PATTERN_LOOSE_OBJECT.matcher(fileName.toString())
					.matches();
		})) {
			for(Iterator<Path> iter = stream.iterator(); iter.hasNext(); iter
					.next()) {
				if(++n > threshold) {
					return true;
				}
			}
		} catch(IOException e) {
			LOG.error(e.getMessage(), e);
		}
		return false;
	}

	private int getLooseObjectLimit() {
		return repo.getConfig().getInt(ConfigConstants.CONFIG_GC_SECTION,
				ConfigConstants.CONFIG_KEY_AUTO, DEFAULT_AUTOLIMIT);
	}
}
