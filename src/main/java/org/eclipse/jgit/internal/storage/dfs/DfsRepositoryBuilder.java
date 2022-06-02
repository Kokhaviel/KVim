/*
 * Copyright (C) 2011, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.dfs;

import java.io.File;
import java.io.IOException;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.BaseRepositoryBuilder;

public abstract class DfsRepositoryBuilder<B extends DfsRepositoryBuilder, R extends DfsRepository>
		extends BaseRepositoryBuilder<B, R> {
	private DfsReaderOptions readerOptions;

	private DfsRepositoryDescription repoDesc;

	public DfsReaderOptions getReaderOptions() {
		return readerOptions;
	}

	public B setReaderOptions(DfsReaderOptions opt) {
		readerOptions = opt;
		return self();
	}

	public DfsRepositoryDescription getRepositoryDescription() {
		return repoDesc;
	}

	public B setRepositoryDescription(DfsRepositoryDescription desc) {
		repoDesc = desc;
		return self();
	}

	@Override
	public B setup() throws IllegalArgumentException, IOException {
		super.setup();
		if(getReaderOptions() == null)
			setReaderOptions(new DfsReaderOptions());
		if(getRepositoryDescription() == null)
			setRepositoryDescription(new DfsRepositoryDescription());
		return self();
	}

	@Override
	public abstract R build() throws IOException;

	@Override
	public B setGitDir(File gitDir) {
		if(gitDir != null)
			throw new IllegalArgumentException();
		return self();
	}

	@Override
	public B setObjectDirectory(File objectDirectory) {
		if(objectDirectory != null)
			throw new IllegalArgumentException();
		return self();
	}

	@Override
	public B addAlternateObjectDirectory(File other) {
		throw new UnsupportedOperationException(
				JGitText.get().unsupportedAlternates);
	}

	@Override
	public B setWorkTree(File workTree) {
		if(workTree != null)
			throw new IllegalArgumentException();
		return self();
	}

	@Override
	public B setIndexFile(File indexFile) {
		if(indexFile != null)
			throw new IllegalArgumentException();
		return self();
	}
}
