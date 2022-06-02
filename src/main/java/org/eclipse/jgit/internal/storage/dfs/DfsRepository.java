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

import org.eclipse.jgit.attributes.AttributesNode;
import org.eclipse.jgit.attributes.AttributesNodeProvider;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.*;

import java.io.IOException;
import java.io.InputStream;
import java.text.MessageFormat;
import java.util.Collections;

public abstract class DfsRepository extends Repository {
	private final DfsConfig config;

	private final DfsRepositoryDescription description;

	protected DfsRepository(DfsRepositoryBuilder builder) {
		super(builder);
		this.config = new DfsConfig();
		this.description = builder.getRepositoryDescription();
	}

	@Override
	public abstract DfsObjDatabase getObjectDatabase();

	public DfsRepositoryDescription getDescription() {
		return description;
	}

	public boolean exists() throws IOException {
		if (getRefDatabase() instanceof DfsRefDatabase) {
			return ((DfsRefDatabase) getRefDatabase()).exists();
		}
		return true;
	}

	@Override
	public void create(boolean bare) throws IOException {
		if (exists())
			throw new IOException(MessageFormat.format(
					JGitText.get().repositoryAlreadyExists, ""));

		String master = Constants.R_HEADS + Constants.MASTER;
		RefUpdate.Result result = updateRef(Constants.HEAD, true).link(master);
		if (result != RefUpdate.Result.NEW)
			throw new IOException(result.name());
	}

	@Override
	public StoredConfig getConfig() {
		return config;
	}

	@Override
	public String getIdentifier() {
		return getDescription().getRepositoryName();
	}

	@Override
	public void notifyIndexChanged(boolean internal) {
	}

	@Override
	public ReflogReader getReflogReader(String refName) {
		throw new UnsupportedOperationException();
	}

	@Override
	public AttributesNodeProvider createAttributesNodeProvider() {
		return new EmptyAttributesNodeProvider();
	}

	private static class EmptyAttributesNodeProvider implements
			AttributesNodeProvider {
		private final EmptyAttributesNode emptyAttributesNode = new EmptyAttributesNode();

		@Override
		public AttributesNode getInfoAttributesNode() {
			return emptyAttributesNode;
		}

		@Override
		public AttributesNode getGlobalAttributesNode() {
			return emptyAttributesNode;
		}

		private static class EmptyAttributesNode extends AttributesNode {

			public EmptyAttributesNode() {
				super(Collections.emptyList());
			}

			@Override
			public void parse(InputStream in) throws IOException {
			}
		}
	}
}
