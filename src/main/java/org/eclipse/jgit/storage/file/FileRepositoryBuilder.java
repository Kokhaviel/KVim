/*
 * Copyright (C) 2010, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.storage.file;

import java.io.File;
import java.io.IOException;

import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.BaseRepositoryBuilder;
import org.eclipse.jgit.lib.Repository;

public class FileRepositoryBuilder extends BaseRepositoryBuilder<FileRepositoryBuilder, Repository> {

	@Override
	public Repository build() throws IOException {
		FileRepository repo = new FileRepository(setup());
		if(isMustExist() && !repo.getObjectDatabase().exists())
			throw new RepositoryNotFoundException(getGitDir());
		return repo;
	}

	public static Repository create(File gitDir) throws IOException {
		return new FileRepositoryBuilder().setGitDir(gitDir).readEnvironment().build();
	}
}
