/*
 * Copyright (C) 2008, Charles O'Farrell <charleso@charleso.org>
 * Copyright (C) 2009, Robin Rosenberg <robin.rosenberg@gmail.com>
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import static org.eclipse.jgit.lib.Constants.R_HEADS;
import static org.eclipse.jgit.lib.Constants.R_REMOTES;
import static org.eclipse.jgit.lib.Constants.R_TAGS;

import java.io.IOException;
import java.io.Writer;

import org.eclipse.jgit.lib.ObjectId;

class FetchHeadRecord {
	ObjectId newValue;
	boolean notForMerge;
	String sourceName;
	URIish sourceURI;

	void write(Writer pw) throws IOException {
		final String type;
		final String name;
		if(sourceName.startsWith(R_HEADS)) {
			type = "branch";
			name = sourceName.substring(R_HEADS.length());
		} else if(sourceName.startsWith(R_TAGS)) {
			type = "tag";
			name = sourceName.substring(R_TAGS.length());
		} else if(sourceName.startsWith(R_REMOTES)) {
			type = "remote branch";
			name = sourceName.substring(R_REMOTES.length());
		} else {
			type = "";
			name = sourceName;
		}

		pw.write(newValue.name());
		pw.write('\t');
		if(notForMerge)
			pw.write("not-for-merge");
		pw.write('\t');
		pw.write(type);
		pw.write(" '");
		pw.write(name);
		pw.write("' of ");
		pw.write(sourceURI.toString());
		pw.write("\n");
	}
}
