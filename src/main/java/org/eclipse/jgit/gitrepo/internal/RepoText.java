/*
 * Copyright (C) 2014, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.gitrepo.internal;

import org.eclipse.jgit.nls.NLS;
import org.eclipse.jgit.nls.TranslationBundle;

public class RepoText extends TranslationBundle {

	public static RepoText get() {
		return NLS.getBundleFor(RepoText.class);
	}

	public String errorIncludeFile;
	public String errorIncludeNotImplemented;
	public String errorNoDefault;
	public String errorNoDefaultFilename;
	public String errorNoFetch;
	public String errorParsingManifestFile;
	public String errorRemoteUnavailable;
	public String invalidManifest;
	public String repoCommitMessage;
}
