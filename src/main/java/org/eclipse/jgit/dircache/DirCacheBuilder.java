/*
 * Copyright (C) 2008-2009, Google Inc.
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.dircache;

import static org.eclipse.jgit.lib.FileMode.TYPE_MASK;
import static org.eclipse.jgit.lib.FileMode.TYPE_TREE;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.Arrays;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;

public class DirCacheBuilder extends BaseDirCacheEditor {
	private boolean sorted;

	protected DirCacheBuilder(DirCache dc, int ecnt) {
		super(dc, ecnt);
	}

	public void add(DirCacheEntry newEntry) {
		if (newEntry.getRawMode() == 0)
			throw new IllegalArgumentException(MessageFormat.format(
					JGitText.get().fileModeNotSetForPath,
					newEntry.getPathString()));
		beforeAdd(newEntry);
		fastAdd(newEntry);
	}

	public void keep(int pos, int cnt) {
		beforeAdd(cache.getEntry(pos));
		fastKeep(pos, cnt);
	}

	public void addTree(byte[] pathPrefix, int stage, ObjectReader reader,
			AnyObjectId tree) throws IOException {
		CanonicalTreeParser p = createTreeParser(pathPrefix, reader, tree);
		while (!p.eof()) {
			if (isTree(p)) {
				p = enterTree(p, reader);
				continue;
			}

			DirCacheEntry first = toEntry(stage, p);
			beforeAdd(first);
			fastAdd(first);
			p = p.next();
			break;
		}

		while (!p.eof()) {
			if (isTree(p)) {
				p = enterTree(p, reader);
			} else {
				fastAdd(toEntry(stage, p));
				p = p.next();
			}
		}
	}

	private static CanonicalTreeParser createTreeParser(byte[] pathPrefix,
			ObjectReader reader, AnyObjectId tree) throws IOException {
		return new CanonicalTreeParser(pathPrefix, reader, tree);
	}

	private static boolean isTree(CanonicalTreeParser p) {
		return (p.getEntryRawMode() & TYPE_MASK) == TYPE_TREE;
	}

	private static CanonicalTreeParser enterTree(CanonicalTreeParser p,
			ObjectReader reader) throws IOException {
		p = p.createSubtreeIterator(reader);
		return p.eof() ? p.next() : p;
	}

	private static DirCacheEntry toEntry(int stage, CanonicalTreeParser i) {
		byte[] buf = i.getEntryPathBuffer();
		int len = i.getEntryPathLength();
		byte[] path = new byte[len];
		System.arraycopy(buf, 0, path, 0, len);

		DirCacheEntry e = new DirCacheEntry(path, stage);
		e.setFileMode(i.getEntryRawMode());
		e.setObjectIdFromRaw(i.idBuffer(), i.idOffset());
		return e;
	}

	@Override
	public void finish() {
		if (!sorted)
			resort();
		replace();
	}

	private void beforeAdd(DirCacheEntry newEntry) {
		if (sorted && entryCnt > 0) {
			final DirCacheEntry lastEntry = entries[entryCnt - 1];
			final int cr = DirCache.cmp(lastEntry, newEntry);
			if (cr > 0) {
				sorted = false;
			} else if (cr == 0) {
				final int peStage = lastEntry.getStage();
				final int dceStage = newEntry.getStage();
				if (peStage == dceStage)
					throw bad(newEntry, JGitText.get().duplicateStagesNotAllowed);
				if (peStage == 0 || dceStage == 0)
					throw bad(newEntry, JGitText.get().mixedStagesNotAllowed);
				if (peStage > dceStage)
					sorted = false;
			}
		}
	}

	private void resort() {
		Arrays.sort(entries, 0, entryCnt, DirCache.ENT_CMP);

		for (int entryIdx = 1; entryIdx < entryCnt; entryIdx++) {
			final DirCacheEntry pe = entries[entryIdx - 1];
			final DirCacheEntry ce = entries[entryIdx];
			final int cr = DirCache.cmp(pe, ce);
			if (cr == 0) {
				final int peStage = pe.getStage();
				final int ceStage = ce.getStage();
				if (peStage == ceStage)
					throw bad(ce, JGitText.get().duplicateStagesNotAllowed);
				if (peStage == 0 || ceStage == 0)
					throw bad(ce, JGitText.get().mixedStagesNotAllowed);
			}
		}

		sorted = true;
	}

	private static IllegalStateException bad(DirCacheEntry a, String msg) {
		return new IllegalStateException(String.format(
				"%s: %d %s",
				msg, a.getStage(), a.getPathString()));
	}
}
