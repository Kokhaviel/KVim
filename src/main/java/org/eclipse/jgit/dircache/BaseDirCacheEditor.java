/*
 * Copyright (C) 2008, Google Inc.
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.dircache;

import static org.eclipse.jgit.lib.FileMode.TYPE_TREE;
import static org.eclipse.jgit.util.Paths.compareSameName;

import java.io.IOException;

import org.eclipse.jgit.errors.DirCacheNameConflictException;

abstract class BaseDirCacheEditor {

	protected DirCache cache;
	protected DirCacheEntry[] entries;
	protected int entryCnt;

	protected BaseDirCacheEditor(DirCache dc, int ecnt) {
		cache = dc;
		entries = new DirCacheEntry[ecnt];
	}

	public DirCache getDirCache() {
		return cache;
	}

	protected void fastAdd(DirCacheEntry newEntry) {
		if(entries.length == entryCnt) {
			final DirCacheEntry[] n = new DirCacheEntry[(entryCnt + 16) * 3 / 2];
			System.arraycopy(entries, 0, n, 0, entryCnt);
			entries = n;
		}
		entries[entryCnt++] = newEntry;
	}

	protected void fastKeep(int pos, int cnt) {
		if(entryCnt + cnt > entries.length) {
			final int m1 = (entryCnt + 16) * 3 / 2;
			final int m2 = entryCnt + cnt;
			final DirCacheEntry[] n = new DirCacheEntry[Math.max(m1, m2)];
			System.arraycopy(entries, 0, n, 0, entryCnt);
			entries = n;
		}

		cache.toArray(pos, entries, entryCnt, cnt);
		entryCnt += cnt;
	}

	public abstract void finish();

	protected void replace() {
		checkNameConflicts();
		if(entryCnt < entries.length / 2) {
			final DirCacheEntry[] n = new DirCacheEntry[entryCnt];
			System.arraycopy(entries, 0, n, 0, entryCnt);
			entries = n;
		}
		cache.replace(entries, entryCnt);
	}

	private void checkNameConflicts() {
		int end = entryCnt - 1;
		for(int eIdx = 0; eIdx < end; eIdx++) {
			DirCacheEntry e = entries[eIdx];
			if(e.getStage() != 0) {
				continue;
			}

			byte[] ePath = e.path;
			int prefixLen = lastSlash(ePath) + 1;

			for(int nIdx = eIdx + 1; nIdx < entryCnt; nIdx++) {
				DirCacheEntry n = entries[nIdx];
				if(n.getStage() != 0) {
					continue;
				}

				byte[] nPath = n.path;
				if(!startsWith(ePath, nPath, prefixLen)) {
					break;
				}

				int s = nextSlash(nPath, prefixLen);
				int m = s < nPath.length ? TYPE_TREE : n.getRawMode();
				int cmp = compareSameName(
						ePath, prefixLen, ePath.length,
						nPath, prefixLen, s, m);
				if(cmp < 0) {
					break;
				} else if(cmp == 0) {
					throw new DirCacheNameConflictException(
							e.getPathString(),
							n.getPathString());
				}
			}
		}
	}

	private static int lastSlash(byte[] path) {
		for(int i = path.length - 1; i >= 0; i--) {
			if(path[i] == '/') {
				return i;
			}
		}
		return -1;
	}

	private static int nextSlash(byte[] b, int p) {
		final int n = b.length;
		for(; p < n; p++) {
			if(b[p] == '/') {
				return p;
			}
		}
		return n;
	}

	private static boolean startsWith(byte[] a, byte[] b, int n) {
		if(b.length < n) {
			return false;
		}
		for(n--; n >= 0; n--) {
			if(a[n] != b[n]) {
				return false;
			}
		}
		return true;
	}

	public boolean commit() throws IOException {
		finish();
		cache.write();
		return cache.commit();
	}
}
