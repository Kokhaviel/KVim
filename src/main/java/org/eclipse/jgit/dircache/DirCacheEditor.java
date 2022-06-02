/*
 * Copyright (C) 2008, 2009, Google Inc.
 * Copyright (C) 2008, 2020 Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.dircache;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.util.Paths;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.eclipse.jgit.dircache.DirCache.cmp;
import static org.eclipse.jgit.dircache.DirCacheTree.peq;
import static org.eclipse.jgit.lib.FileMode.TYPE_TREE;

public class DirCacheEditor extends BaseDirCacheEditor {
	private static final Comparator<PathEdit> EDIT_CMP = (PathEdit o1,
														  PathEdit o2) -> {
		final byte[] a = o1.path;
		final byte[] b = o2.path;
		return cmp(a, a.length, b, b.length);
	};

	private final List<PathEdit> edits;
	private int editIdx;

	protected DirCacheEditor(DirCache dc, int ecnt) {
		super(dc, ecnt);
		edits = new ArrayList<>();
	}

	public void add(PathEdit edit) {
		edits.add(edit);
	}

	@Override
	public boolean commit() throws IOException {
		if(edits.isEmpty()) {
			cache.unlock();
			return true;
		}
		return super.commit();
	}

	@Override
	public void finish() {
		if(!edits.isEmpty()) {
			applyEdits();
			replace();
		}
	}

	private void applyEdits() {
		edits.sort(EDIT_CMP);
		editIdx = 0;

		final int maxIdx = cache.getEntryCount();
		int lastIdx = 0;
		while(editIdx < edits.size()) {
			PathEdit e = edits.get(editIdx++);
			int eIdx = cache.findEntry(lastIdx, e.path, e.path.length);
			final boolean missing = eIdx < 0;
			if(eIdx < 0)
				eIdx = -(eIdx + 1);
			final int cnt = Math.min(eIdx, maxIdx) - lastIdx;
			if(cnt > 0)
				fastKeep(lastIdx, cnt);

			if(e instanceof DeletePath) {
				lastIdx = missing ? eIdx : cache.nextEntry(eIdx);
				continue;
			}
			if(e instanceof DeleteTree) {
				lastIdx = cache.nextEntry(e.path, e.path.length, eIdx);
				continue;
			}

			if(missing) {
				DirCacheEntry ent = new DirCacheEntry(e.path);
				e.apply(ent);
				if(ent.getRawMode() == 0) {
					throw new IllegalArgumentException(MessageFormat.format(
							JGitText.get().fileModeNotSetForPath,
							ent.getPathString()));
				}
				lastIdx = e.replace
						? deleteOverlappingSubtree(ent, eIdx)
						: eIdx;
				fastAdd(ent);
			} else {
				lastIdx = cache.nextEntry(eIdx);
				if(lastIdx > eIdx + 1) {
					DirCacheEntry[] tmp = new DirCacheEntry[lastIdx - eIdx];
					int n = 0;
					for(int i = eIdx; i < lastIdx; i++) {
						DirCacheEntry ent = cache.getEntry(i);
						e.apply(ent);
						if(ent.getStage() == DirCacheEntry.STAGE_0) {
							fastAdd(ent);
							n = 0;
							break;
						}
						tmp[n++] = ent;
					}
					for(int i = 0; i < n; i++) {
						fastAdd(tmp[i]);
					}
				} else {
					DirCacheEntry ent = cache.getEntry(eIdx);
					e.apply(ent);
					fastAdd(ent);
				}
			}
		}

		final int cnt = maxIdx - lastIdx;
		if(cnt > 0)
			fastKeep(lastIdx, cnt);
	}

	private int deleteOverlappingSubtree(DirCacheEntry ent, int eIdx) {
		byte[] entPath = ent.path;
		int entLen = entPath.length;

		for(int p = pdir(entPath, entLen); p > 0; p = pdir(entPath, p)) {
			int i = findEntry(entPath, p);
			if(i >= 0) {
				int n = --entryCnt - i;
				System.arraycopy(entries, i + 1, entries, i, n);
				break;
			}

			i = -(i + 1);
			if(i < entryCnt && inDir(entries[i], entPath, p)) {
				break;
			}
		}

		int maxEnt = cache.getEntryCount();
		if(eIdx >= maxEnt) {
			return maxEnt;
		}

		DirCacheEntry next = cache.getEntry(eIdx);
		if(Paths.compare(next.path, 0, next.path.length, 0,
				entPath, 0, entLen, TYPE_TREE) < 0) {
			insertEdit(new DeleteTree(entPath));
			return eIdx;
		}

		while(eIdx < maxEnt && inDir(cache.getEntry(eIdx), entPath, entLen)) {
			eIdx++;
		}
		return eIdx;
	}

	private int findEntry(byte[] p, int pLen) {
		int low = 0;
		int high = entryCnt;
		while(low < high) {
			int mid = (low + high) >>> 1;
			int cmp = cmp(p, pLen, entries[mid]);
			if(cmp < 0) {
				high = mid;
			} else if(cmp == 0) {
				while(mid > 0 && cmp(p, pLen, entries[mid - 1]) == 0) {
					mid--;
				}
				return mid;
			} else {
				low = mid + 1;
			}
		}
		return -(low + 1);
	}

	private void insertEdit(DeleteTree d) {
		for(int i = editIdx; i < edits.size(); i++) {
			int cmp = EDIT_CMP.compare(d, edits.get(i));
			if(cmp < 0) {
				edits.add(i, d);
				return;
			} else if(cmp == 0) {
				return;
			}
		}
		edits.add(d);
	}

	private static boolean inDir(DirCacheEntry e, byte[] path, int pLen) {
		return e.path.length > pLen && e.path[pLen] == '/'
				&& peq(path, e.path, pLen);
	}

	private static int pdir(byte[] path, int e) {
		for(e--; e > 0; e--) {
			if(path[e] == '/') {
				return e;
			}
		}
		return 0;
	}

	public abstract static class PathEdit {
		final byte[] path;
		boolean replace = true;

		public PathEdit(String entryPath) {
			path = Constants.encode(entryPath);
		}

		PathEdit(byte[] path) {
			this.path = path;
		}

		public PathEdit(DirCacheEntry ent) {
			path = ent.path;
		}

		public abstract void apply(DirCacheEntry ent);

		@Override
		public String toString() {
			String p = DirCacheEntry.toString(path);
			return getClass().getSimpleName() + '[' + p + ']';
		}
	}

	public static final class DeletePath extends PathEdit {

		public DeletePath(String entryPath) {
			super(entryPath);
		}

		@Override
		public void apply(DirCacheEntry ent) {
			throw new UnsupportedOperationException(JGitText.get().noApplyInDelete);
		}
	}

	public static final class DeleteTree extends PathEdit {

		DeleteTree(byte[] path) {
			super(appendSlash(path));
		}

		private static byte[] appendSlash(byte[] path) {
			int n = path.length;
			if(n > 0 && path[n - 1] != '/') {
				byte[] r = new byte[n + 1];
				System.arraycopy(path, 0, r, 0, n);
				r[n] = '/';
				return r;
			}
			return path;
		}

		@Override
		public void apply(DirCacheEntry ent) {
			throw new UnsupportedOperationException(JGitText.get().noApplyInDelete);
		}
	}
}
