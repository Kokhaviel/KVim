/*
 * Copyright (C) 2014, Arthur Daussy <arthur.daussy@obeo.fr>
 * Copyright (C) 2015, Christian Halstrick <christian.halstrick@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.internal.storage.file;

import java.io.File;
import java.io.IOException;

import org.eclipse.jgit.attributes.AttributesNode;
import org.eclipse.jgit.lib.CoreConfig;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.util.FS;

public class GlobalAttributesNode extends AttributesNode {
	final Repository repository;

	public GlobalAttributesNode(Repository repository) {
		this.repository = repository;
	}

	public AttributesNode load() throws IOException {
		AttributesNode r = new AttributesNode();

		FS fs = repository.getFS();
		String path = repository.getConfig().get(CoreConfig.KEY)
				.getAttributesFile();
		if(path != null) {
			File attributesFile;
			if(path.startsWith("~/")) {
				attributesFile = fs.resolve(fs.userHome(),
						path.substring(2));
			} else {
				attributesFile = fs.resolve(null, path);
			}
			FileRepository.AttributesNodeProviderImpl.loadRulesFromFile(r, attributesFile);
		}
		return r.getRules().isEmpty() ? null : r;
	}
}
