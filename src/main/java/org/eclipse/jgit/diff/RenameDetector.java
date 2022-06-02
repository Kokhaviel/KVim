/*
 * Copyright (C) 2010, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.diff;

import static org.eclipse.jgit.storage.pack.PackConfig.DEFAULT_BIG_FILE_THRESHOLD;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

import org.eclipse.jgit.api.errors.CanceledException;
import org.eclipse.jgit.diff.DiffEntry.ChangeType;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.ProgressMonitor;

public class RenameDetector {
	private static final int EXACT_RENAME_SCORE = 100;

	private static final Comparator<DiffEntry> DIFF_COMPARATOR = new Comparator<DiffEntry>() {

		@Override
		public int compare(DiffEntry a, DiffEntry b) {
			int cmp = nameOf(a).compareTo(nameOf(b));
			if(cmp == 0)
				cmp = sortOf(a.getChangeType()) - sortOf(b.getChangeType());
			return cmp;
		}

		private String nameOf(DiffEntry ent) {
			if(ent.changeType == ChangeType.DELETE)
				return ent.oldPath;
			return ent.newPath;
		}

		private int sortOf(ChangeType changeType) {
			switch(changeType) {
				case DELETE:
					return 1;
				case ADD:
					return 2;
				default:
					return 10;
			}
		}
	};

	private List<DiffEntry> entries;
	private List<DiffEntry> deleted;
	private List<DiffEntry> added;
	private boolean done;
	private final ObjectReader objectReader;
	private final int renameLimit;

	public RenameDetector(ObjectReader reader, DiffConfig cfg) {
		objectReader = reader.newReader();
		renameLimit = cfg.getRenameLimit();
		reset();
	}

	public int getRenameScore() {
		return 60;
	}

	public int getRenameLimit() {
		return renameLimit;
	}

	public int getBigFileThreshold() {
		return DEFAULT_BIG_FILE_THRESHOLD;
	}

	public boolean getSkipContentRenamesForBinaryFiles() {
		return false;
	}

	public void addAll(Collection<DiffEntry> entriesToAdd) {
		if(done)
			throw new IllegalStateException(JGitText.get().renamesAlreadyFound);

		for(DiffEntry entry : entriesToAdd) {
			switch(entry.getChangeType()) {
				case ADD:
					added.add(entry);
					break;

				case DELETE:
					deleted.add(entry);
					break;

				case MODIFY:
					if(sameType(entry.getOldMode(), entry.getNewMode())) {
						entries.add(entry);
					} else {
						List<DiffEntry> tmp = DiffEntry.breakModify(entry);
						deleted.add(tmp.get(0));
						added.add(tmp.get(1));
					}
					break;

				case COPY:
				case RENAME:
				default:
					entries.add(entry);
			}
		}
	}

	public void add(DiffEntry entry) {
		addAll(Collections.singletonList(entry));
	}

	public List<DiffEntry> compute() throws IOException {
		try {
			return compute(NullProgressMonitor.INSTANCE);
		} catch(CanceledException e) {
			return Collections.emptyList();
		}
	}

	public List<DiffEntry> compute(ProgressMonitor pm)
			throws IOException, CanceledException {
		if(!done) {
			try {
				return compute(objectReader, pm);
			} finally {
				objectReader.close();
			}
		}
		return Collections.unmodifiableList(entries);
	}

	public List<DiffEntry> compute(ObjectReader reader, ProgressMonitor pm)
			throws IOException, CanceledException {
		final ContentSource cs = ContentSource.create(reader);
		return compute(new ContentSource.Pair(cs, cs), pm);
	}

	public List<DiffEntry> compute(ContentSource.Pair reader, ProgressMonitor pm)
			throws IOException, CanceledException {
		if(!done) {
			done = true;

			if(pm == null)
				pm = NullProgressMonitor.INSTANCE;

			if(!added.isEmpty() && !deleted.isEmpty())
				findExactRenames(pm);

			if(!added.isEmpty() && !deleted.isEmpty())
				findContentRenames(reader, pm);

			entries.addAll(added);
			added = null;

			entries.addAll(deleted);
			deleted = null;

			entries.sort(DIFF_COMPARATOR);
		}
		return Collections.unmodifiableList(entries);
	}

	public void reset() {
		entries = new ArrayList<>();
		deleted = new ArrayList<>();
		added = new ArrayList<>();
		done = false;
	}

	private void advanceOrCancel(ProgressMonitor pm) throws CanceledException {
		if(pm.isCancelled()) {
			throw new CanceledException(JGitText.get().renameCancelled);
		}
		pm.update(1);
	}

	private void findContentRenames(ContentSource.Pair reader,
									ProgressMonitor pm)
			throws IOException, CanceledException {
		int cnt = Math.max(added.size(), deleted.size());
		if(getRenameLimit() == 0 || cnt <= getRenameLimit()) {
			SimilarityRenameDetector d;

			d = new SimilarityRenameDetector(reader, deleted, added);
			d.setRenameScore(getRenameScore());
			d.setBigFileThreshold(getBigFileThreshold());
			d.setSkipBinaryFiles(getSkipContentRenamesForBinaryFiles());
			d.compute(pm);
			deleted = d.getLeftOverSources();
			added = d.getLeftOverDestinations();
			entries.addAll(d.getMatches());
		}
	}

	@SuppressWarnings("unchecked")
	private void findExactRenames(ProgressMonitor pm)
			throws CanceledException {
		pm.beginTask(JGitText.get().renamesFindingExact,
				added.size() + added.size() + deleted.size()
						+ added.size() * deleted.size());

		HashMap<AbbreviatedObjectId, Object> deletedMap = populateMap(deleted, pm);
		HashMap<AbbreviatedObjectId, Object> addedMap = populateMap(added, pm);

		ArrayList<DiffEntry> uniqueAdds = new ArrayList<>(added.size());
		ArrayList<List<DiffEntry>> nonUniqueAdds = new ArrayList<>();

		for(Object o : addedMap.values()) {
			if(o instanceof DiffEntry)
				uniqueAdds.add((DiffEntry) o);
			else
				nonUniqueAdds.add((List<DiffEntry>) o);
		}

		ArrayList<DiffEntry> left = new ArrayList<>(added.size());

		for(DiffEntry a : uniqueAdds) {
			Object del = deletedMap.get(a.newId);
			if(del instanceof DiffEntry) {
				DiffEntry e = (DiffEntry) del;
				if(sameType(e.oldMode, a.newMode)) {
					e.changeType = ChangeType.RENAME;
					entries.add(exactRename(e, a));
				} else {
					left.add(a);
				}
			} else if(del != null) {
				List<DiffEntry> list = (List<DiffEntry>) del;
				DiffEntry best = bestPathMatch(a, list);
				if(best != null) {
					best.changeType = ChangeType.RENAME;
					entries.add(exactRename(best, a));
				} else {
					left.add(a);
				}
			} else {
				left.add(a);
			}
			advanceOrCancel(pm);
		}

		for(List<DiffEntry> adds : nonUniqueAdds) {
			Object o = deletedMap.get(adds.get(0).newId);
			if(o instanceof DiffEntry) {
				DiffEntry d = (DiffEntry) o;
				DiffEntry best = bestPathMatch(d, adds);
				if(best != null) {
					d.changeType = ChangeType.RENAME;
					entries.add(exactRename(d, best));
					for(DiffEntry a : adds) {
						if(a != best) {
							if(sameType(d.oldMode, a.newMode)) {
								entries.add(exactCopy(d, a));
							} else {
								left.add(a);
							}
						}
					}
				} else {
					left.addAll(adds);
				}
			} else if(o != null) {
				List<DiffEntry> dels = (List<DiffEntry>) o;
				long[] matrix = new long[dels.size() * adds.size()];
				int mNext = 0;
				for(int delIdx = 0; delIdx < dels.size(); delIdx++) {
					String deletedName = dels.get(delIdx).oldPath;

					for(int addIdx = 0; addIdx < adds.size(); addIdx++) {
						String addedName = adds.get(addIdx).newPath;

						int score = SimilarityRenameDetector.nameScore(addedName, deletedName);
						matrix[mNext] = SimilarityRenameDetector.encode(score, delIdx, addIdx);
						mNext++;
						if(pm.isCancelled()) {
							throw new CanceledException(
									JGitText.get().renameCancelled);
						}
					}
				}

				Arrays.sort(matrix);

				for(--mNext; mNext >= 0; mNext--) {
					long ent = matrix[mNext];
					int delIdx = SimilarityRenameDetector.srcFile(ent);
					int addIdx = SimilarityRenameDetector.dstFile(ent);
					DiffEntry d = dels.get(delIdx);
					DiffEntry a = adds.get(addIdx);

					if(a == null) {
						advanceOrCancel(pm);
						continue;
					}

					ChangeType type;
					if(d.changeType == ChangeType.DELETE) {
						d.changeType = ChangeType.RENAME;
						type = ChangeType.RENAME;
					} else {
						type = ChangeType.COPY;
					}

					entries.add(DiffEntry.pair(type, d, a, 100));
					adds.set(addIdx, null);
					advanceOrCancel(pm);
				}
			} else {
				left.addAll(adds);
			}
			advanceOrCancel(pm);
		}
		added = left;

		deleted = new ArrayList<>(deletedMap.size());
		for(Object o : deletedMap.values()) {
			if(o instanceof DiffEntry) {
				DiffEntry e = (DiffEntry) o;
				if(e.changeType == ChangeType.DELETE)
					deleted.add(e);
			} else {
				List<DiffEntry> list = (List<DiffEntry>) o;
				for(DiffEntry e : list) {
					if(e.changeType == ChangeType.DELETE)
						deleted.add(e);
				}
			}
		}
		pm.endTask();
	}

	private static DiffEntry bestPathMatch(DiffEntry src, List<DiffEntry> list) {
		DiffEntry best = null;
		int score = -1;

		for(DiffEntry d : list) {
			if(sameType(mode(d), mode(src))) {
				int tmp = SimilarityRenameDetector
						.nameScore(path(d), path(src));
				if(tmp > score) {
					best = d;
					score = tmp;
				}
			}
		}

		return best;
	}

	@SuppressWarnings("unchecked")
	private HashMap<AbbreviatedObjectId, Object> populateMap(
			List<DiffEntry> diffEntries, ProgressMonitor pm)
			throws CanceledException {
		HashMap<AbbreviatedObjectId, Object> map = new HashMap<>();
		for(DiffEntry de : diffEntries) {
			Object old = map.put(id(de), de);
			if(old instanceof DiffEntry) {
				ArrayList<DiffEntry> list = new ArrayList<>(2);
				list.add((DiffEntry) old);
				list.add(de);
				map.put(id(de), list);
			} else if(old != null) {
				((List<DiffEntry>) old).add(de);
				map.put(id(de), old);
			}
			advanceOrCancel(pm);
		}
		return map;
	}

	private static String path(DiffEntry de) {
		return de.changeType == ChangeType.DELETE ? de.oldPath : de.newPath;
	}

	private static FileMode mode(DiffEntry de) {
		return de.changeType == ChangeType.DELETE ? de.oldMode : de.newMode;
	}

	private static AbbreviatedObjectId id(DiffEntry de) {
		return de.changeType == ChangeType.DELETE ? de.oldId : de.newId;
	}

	static boolean sameType(FileMode a, FileMode b) {
		int aType = a.getBits() & FileMode.TYPE_MASK;
		int bType = b.getBits() & FileMode.TYPE_MASK;
		return aType == bType;
	}

	private static DiffEntry exactRename(DiffEntry src, DiffEntry dst) {
		return DiffEntry.pair(ChangeType.RENAME, src, dst, EXACT_RENAME_SCORE);
	}

	private static DiffEntry exactCopy(DiffEntry src, DiffEntry dst) {
		return DiffEntry.pair(ChangeType.COPY, src, dst, EXACT_RENAME_SCORE);
	}
}
