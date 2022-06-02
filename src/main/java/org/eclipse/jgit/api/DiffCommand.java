/*
 * Copyright (C) 2011, Tomasz Zarna <Tomasz.Zarna@pl.ibm.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.api;

import static org.eclipse.jgit.lib.Constants.HEAD;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.eclipse.jgit.util.io.NullOutputStream;

public class DiffCommand extends GitCommand<List<DiffEntry>> {

	private AbstractTreeIterator oldTree;
	private AbstractTreeIterator newTree;
	private boolean cached;
	private final TreeFilter pathFilter = TreeFilter.ALL;
	private boolean showNameAndStatusOnly;
	private OutputStream out;
	private String sourcePrefix;
	private String destinationPrefix;
	private final ProgressMonitor monitor = NullProgressMonitor.INSTANCE;

	protected DiffCommand(Repository repo) {
		super(repo);
	}

	private DiffFormatter getDiffFormatter() {
		return out != null && !showNameAndStatusOnly
				? new DiffFormatter(new BufferedOutputStream(out)) : new DiffFormatter(NullOutputStream.INSTANCE);
	}

	@Override
	public List<DiffEntry> call() throws GitAPIException {
		try(DiffFormatter diffFmt = getDiffFormatter()) {
			diffFmt.setRepository(repo);
			diffFmt.setProgressMonitor(monitor);
			if(cached) {
				if(oldTree == null) {
					ObjectId head = repo.resolve(HEAD + "^{tree}");
					if(head == null) throw new NoHeadException(JGitText.get().cannotReadTree);
					CanonicalTreeParser p = new CanonicalTreeParser();
					try(ObjectReader reader = repo.newObjectReader()) {
						p.reset(reader, head);
					}
					oldTree = p;
				}
				newTree = new DirCacheIterator(repo.readDirCache());
			} else {
				if(oldTree == null) {
					oldTree = new DirCacheIterator(repo.readDirCache());
				}
				if(newTree == null) {
					newTree = new FileTreeIterator(repo);
				}
			}

			diffFmt.setPathFilter(pathFilter);

			List<DiffEntry> result = diffFmt.scan(oldTree, newTree);
			if(showNameAndStatusOnly) {
				return result;
			}
			if(destinationPrefix != null) {
				diffFmt.setNewPrefix(destinationPrefix);
			}
			if(sourcePrefix != null) {
				diffFmt.setOldPrefix(sourcePrefix);
			}
			diffFmt.format(result);
			diffFmt.flush();
			return result;
		} catch(IOException e) {
			throw new JGitInternalException(e.getMessage(), e);
		}
	}
}
