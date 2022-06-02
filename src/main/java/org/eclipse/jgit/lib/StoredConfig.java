/*
 * Copyright (C) 2010, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.lib;

import java.io.IOException;

import org.eclipse.jgit.errors.ConfigInvalidException;

public abstract class StoredConfig extends Config {

	public StoredConfig() {
		super();
	}

	public StoredConfig(Config defaultConfig) {
		super(defaultConfig);
	}

	public abstract void load() throws IOException, ConfigInvalidException;

	public abstract void save() throws IOException;

	@Override
	public void clear() {
		super.clear();
	}
}
