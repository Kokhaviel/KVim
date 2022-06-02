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

import static org.eclipse.jgit.diff.DiffEntry.Side.*;
import static org.eclipse.jgit.storage.pack.PackConfig.*;

import java.io.IOException;
import java.util.*;

import org.eclipse.jgit.api.errors.CanceledException;
import org.eclipse.jgit.diff.DiffEntry.ChangeType;
import org.eclipse.jgit.diff.SimilarityIndex.TableFullException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.*;

class SimilarityRenameDetector {

	private static final int BITS_PER_INDEX = 28,
			INDEX_MASK = (1 << BITS_PER_INDEX) - 1, SCORE_SHIFT = 2 * BITS_PER_INDEX;

	private final ContentSource.Pair reader;
	private List<DiffEntry> srcs;
	private List<DiffEntry> dsts;
	private long[] matrix;
	private int renameScore = 60;
	private int bigFileThreshold = DEFAULT_BIG_FILE_THRESHOLD;
	private boolean skipBinaryFiles = false;
	private List<DiffEntry> out;

	SimilarityRenameDetector(ContentSource.Pair reader, List<DiffEntry> srcs,
							 List<DiffEntry> dsts) {
		this.reader = reader;
		this.srcs = srcs;
		this.dsts = dsts;
	}

	void setRenameScore(int score) {
		renameScore = score;
	}

	void setBigFileThreshold(int threshold) {
		bigFileThreshold = threshold;
	}

	void setSkipBinaryFiles(boolean value) {
		skipBinaryFiles = value;
	}

	void compute(ProgressMonitor pm) throws IOException, CanceledException {
		if(pm == null)
			pm = NullProgressMonitor.INSTANCE;

		pm.beginTask(JGitText.get().renamesFindingByContent,
				2 * srcs.size() * dsts.size());

		int mNext = buildMatrix(pm);
		out = new ArrayList<>(Math.min(mNext, dsts.size()));

		for(--mNext; mNext >= 0; mNext--) {
			if(pm.isCancelled()) {
				throw new CanceledException(JGitText.get().renameCancelled);
			}
			long ent = matrix[mNext];
			int sIdx = srcFile(ent);
			int dIdx = dstFile(ent);
			DiffEntry s = srcs.get(sIdx);
			DiffEntry d = dsts.get(dIdx);

			if(d == null) {
				pm.update(1);
				continue;
			}

			ChangeType type;
			if(s.changeType == ChangeType.DELETE) {
				s.changeType = ChangeType.RENAME;
				type = ChangeType.RENAME;
			} else {
				type = ChangeType.COPY;
			}

			out.add(DiffEntry.pair(type, s, d, score(ent)));
			dsts.set(dIdx, null);
			pm.update(1);
		}

		srcs = compactSrcList(srcs);
		dsts = compactDstList(dsts);
		pm.endTask();
	}

	List<DiffEntry> getMatches() {
		return out;
	}

	List<DiffEntry> getLeftOverSources() {
		return srcs;
	}

	List<DiffEntry> getLeftOverDestinations() {
		return dsts;
	}

	private static List<DiffEntry> compactSrcList(List<DiffEntry> in) {
		ArrayList<DiffEntry> r = new ArrayList<>(in.size());
		for(DiffEntry e : in) {
			if(e.changeType == ChangeType.DELETE)
				r.add(e);
		}
		return r;
	}

	private static List<DiffEntry> compactDstList(List<DiffEntry> in) {
		ArrayList<DiffEntry> r = new ArrayList<>(in.size());
		for(DiffEntry e : in) {
			if(e != null)
				r.add(e);
		}
		return r;
	}

	private int buildMatrix(ProgressMonitor pm) throws IOException, CanceledException {
		matrix = new long[srcs.size() * dsts.size()];

		long[] srcSizes = new long[srcs.size()];
		long[] dstSizes = new long[dsts.size()];
		BitSet dstTooLarge = null;

		int mNext = 0;
		SRC:
		for(int srcIdx = 0; srcIdx < srcs.size(); srcIdx++) {
			DiffEntry srcEnt = srcs.get(srcIdx);
			if(!isFile(srcEnt.oldMode)) {
				pm.update(dsts.size());
				continue;
			}

			SimilarityIndex s = null;

			for(int dstIdx = 0; dstIdx < dsts.size(); dstIdx++) {
				if(pm.isCancelled()) {
					throw new CanceledException(
							JGitText.get().renameCancelled);
				}

				DiffEntry dstEnt = dsts.get(dstIdx);

				if(!isFile(dstEnt.newMode)) {
					pm.update(1);
					continue;
				}

				if(!RenameDetector.sameType(srcEnt.oldMode, dstEnt.newMode)) {
					pm.update(1);
					continue;
				}

				if(dstTooLarge != null && dstTooLarge.get(dstIdx)) {
					pm.update(1);
					continue;
				}

				long srcSize = srcSizes[srcIdx];
				if(srcSize == 0) {
					srcSize = size(OLD, srcEnt) + 1;
					srcSizes[srcIdx] = srcSize;
				}

				long dstSize = dstSizes[dstIdx];
				if(dstSize == 0) {
					dstSize = size(NEW, dstEnt) + 1;
					dstSizes[dstIdx] = dstSize;
				}

				long max = Math.max(srcSize, dstSize);
				long min = Math.min(srcSize, dstSize);
				if(min * 100 / max < renameScore) {
					pm.update(1);
					continue;
				}

				if(max > bigFileThreshold) {
					pm.update(1);
					continue;
				}

				if(s == null) {
					try {
						ObjectLoader loader = reader.open(OLD, srcEnt);
						if(skipBinaryFiles && SimilarityIndex.isBinary(loader)) {
							pm.update(1);
							continue SRC;
						}
						s = hash(loader);
					} catch(TableFullException tableFull) {
						continue SRC;
					}
				}

				SimilarityIndex d;
				try {
					ObjectLoader loader = reader.open(NEW, dstEnt);
					if(skipBinaryFiles && SimilarityIndex.isBinary(loader)) {
						pm.update(1);
						continue;
					}
					d = hash(loader);
				} catch(TableFullException tableFull) {
					if(dstTooLarge == null)
						dstTooLarge = new BitSet(dsts.size());
					dstTooLarge.set(dstIdx);
					pm.update(1);
					continue;
				}

				int contentScore = s.score(d, 10000);

				int nameScore = nameScore(srcEnt.oldPath, dstEnt.newPath) * 100;

				int score = (contentScore * 99 + nameScore) / 10000;

				if(score < renameScore) {
					pm.update(1);
					continue;
				}

				matrix[mNext++] = encode(score, srcIdx, dstIdx);
				pm.update(1);
			}
		}

		Arrays.sort(matrix, 0, mNext);
		return mNext;
	}

	static int nameScore(String a, String b) {
		int aDirLen = a.lastIndexOf('/') + 1;
		int bDirLen = b.lastIndexOf('/') + 1;

		int dirMin = Math.min(aDirLen, bDirLen), dirMax = Math.max(aDirLen, bDirLen);

		final int dirScoreLtr, dirScoreRtl;

		if(dirMax == 0) {
			dirScoreLtr = 100;
			dirScoreRtl = 100;
		} else {
			int dirSim = 0;
			for(; dirSim < dirMin; dirSim++) {
				if(a.charAt(dirSim) != b.charAt(dirSim))
					break;
			}
			dirScoreLtr = (dirSim * 100) / dirMax;

			if(dirScoreLtr == 100) dirScoreRtl = 100;
			else {
				for(dirSim = 0; dirSim < dirMin; dirSim++) {
					if(a.charAt(aDirLen - 1 - dirSim) != b.charAt(bDirLen - 1
							- dirSim))
						break;
				}
				dirScoreRtl = (dirSim * 100) / dirMax;
			}
		}

		int fileMin = Math.min(a.length() - aDirLen, b.length() - bDirLen);
		int fileMax = Math.max(a.length() - aDirLen, b.length() - bDirLen);

		int fileSim = 0;
		for(; fileSim < fileMin; fileSim++) {
			if(a.charAt(a.length() - 1 - fileSim) != b.charAt(b.length() - 1 - fileSim))
				break;
		}
		int fileScore = (fileSim * 100) / fileMax;

		return (((dirScoreLtr + dirScoreRtl) * 25) + (fileScore * 50)) / 100;
	}

	private SimilarityIndex hash(ObjectLoader objectLoader)
			throws IOException, TableFullException {
		SimilarityIndex r = new SimilarityIndex();
		r.hash(objectLoader);
		r.sort();
		return r;
	}

	private long size(DiffEntry.Side side, DiffEntry ent) throws IOException {
		return reader.size(side, ent);
	}

	private static int score(long value) {
		return (int) (value >>> SCORE_SHIFT);
	}

	static int srcFile(long value) {
		return decodeFile(((int) (value >>> BITS_PER_INDEX)) & INDEX_MASK);
	}

	static int dstFile(long value) {
		return decodeFile(((int) value) & INDEX_MASK);
	}

	static long encode(int score, int srcIdx, int dstIdx) {
		return (((long) score) << SCORE_SHIFT) | (encodeFile(srcIdx) << BITS_PER_INDEX) | encodeFile(dstIdx);
	}

	private static long encodeFile(int idx) {
		return INDEX_MASK - idx;
	}

	private static int decodeFile(int v) {
		return INDEX_MASK - v;
	}

	private static boolean isFile(FileMode mode) {
		return (mode.getBits() & FileMode.TYPE_MASK) == FileMode.TYPE_FILE;
	}
}
