/*
 * Copyright (C) 2010, Christian Halstrick <christian.halstrick@sap.com>
 * Copyright (C) 2010, Stefan Lay <stefan.lay@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.api;

import static org.eclipse.jgit.lib.Constants.OBJ_BLOB;
import static org.eclipse.jgit.lib.FileMode.GITLINK;
import static org.eclipse.jgit.lib.FileMode.TYPE_GITLINK;
import static org.eclipse.jgit.lib.FileMode.TYPE_TREE;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedList;

import org.eclipse.jgit.api.errors.FilterFailedException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.api.errors.NoFilepatternException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuildIterator;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.NameConflictTreeWalk;
import org.eclipse.jgit.treewalk.TreeWalk.OperationType;
import org.eclipse.jgit.treewalk.WorkingTreeIterator;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;

public class AddCommand extends GitCommand<DirCache> {

	private final Collection<String> filepatterns;
	private WorkingTreeIterator workingTreeIterator;
	private boolean update = false;

	public AddCommand(Repository repo) {
		super(repo);
		filepatterns = new LinkedList<>();
	}

	public AddCommand addFilepattern(String filepattern) {
		checkCallable();
		filepatterns.add(filepattern);
		return this;
	}

	@Override
	public DirCache call() throws GitAPIException {

		if(filepatterns.isEmpty())
			throw new NoFilepatternException(JGitText.get().atLeastOnePatternIsRequired);
		checkCallable();
		DirCache dc = null;
		boolean addAll = filepatterns.contains(".");

		try(ObjectInserter inserter = repo.newObjectInserter();
			NameConflictTreeWalk tw = new NameConflictTreeWalk(repo)) {
			tw.setOperationType(OperationType.CHECKIN_OP);
			dc = repo.lockDirCache();

			DirCacheBuilder builder = dc.builder();
			tw.addTree(new DirCacheBuildIterator(builder));
			if(workingTreeIterator == null)
				workingTreeIterator = new FileTreeIterator(repo);
			workingTreeIterator.setDirCacheIterator(tw, 0);
			tw.addTree(workingTreeIterator);
			if(!addAll)
				tw.setFilter(PathFilterGroup.createFromStrings(filepatterns));

			byte[] lastAdded = null;

			while(tw.next()) {
				DirCacheIterator c = tw.getTree(0);
				WorkingTreeIterator f = tw.getTree(1);
				if(c == null && f != null && f.isEntryIgnored()) {
					continue;
				} else if(c == null && update) {
					continue;
				}

				DirCacheEntry entry = c != null ? c.getDirCacheEntry() : null;
				if(entry != null && entry.getStage() > 0
						&& lastAdded != null
						&& lastAdded.length == tw.getPathLength()
						&& tw.isPathPrefix(lastAdded, lastAdded.length) == 0) {
					continue;
				}

				if(tw.isSubtree() && !tw.isDirectoryFileConflict()) {
					tw.enterSubtree();
					continue;
				}

				if(f == null) {
					if(entry != null
							&& (!update || GITLINK == entry.getFileMode())) {
						builder.add(entry);
					}
					continue;
				}

				if(entry != null && entry.isAssumeValid()) {
					builder.add(entry);
					continue;
				}

				if((f.getEntryRawMode() == TYPE_TREE
						&& f.getIndexFileMode(c) != FileMode.GITLINK) ||
						(f.getEntryRawMode() == TYPE_GITLINK
								&& f.getIndexFileMode(c) == FileMode.TREE)) {
					tw.enterSubtree();
					continue;
				}

				byte[] path = tw.getRawPath();
				if(entry == null || entry.getStage() > 0) {
					entry = new DirCacheEntry(path);
				}
				FileMode mode = f.getIndexFileMode(c);
				entry.setFileMode(mode);

				if(GITLINK != mode) {
					entry.setLength(f.getEntryLength());
					entry.setLastModified(f.getEntryLastModifiedInstant());
					long len = f.getEntryContentLength();
					try(InputStream in = f.openEntryStream()) {
						ObjectId id = inserter.insert(OBJ_BLOB, len, in);
						entry.setObjectId(id);
					}
				} else {
					entry.setLength(0);
					entry.setLastModified(Instant.ofEpochSecond(0));
					entry.setObjectId(f.getEntryObjectId());
				}
				builder.add(entry);
				lastAdded = path;
			}
			inserter.flush();
			builder.commit();
			setCallable(false);
		} catch(IOException e) {
			Throwable cause = e.getCause();
			if(cause instanceof FilterFailedException)
				throw (FilterFailedException) cause;
			throw new JGitInternalException(
					JGitText.get().exceptionCaughtDuringExecutionOfAddCommand, e);
		} finally {
			if(dc != null)
				dc.unlock();
		}

		return dc;
	}

	public AddCommand setUpdate(boolean update) {
		this.update = update;
		return this;
	}
}
