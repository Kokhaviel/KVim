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

import org.eclipse.jgit.nls.NLS;
import org.eclipse.jgit.nls.TranslationBundle;

public class DfsText extends TranslationBundle {

	public static DfsText get() {
		return NLS.getBundleFor(DfsText.class);
	}

	public String cannotReadIndex;
	public String shortReadOfBlock;
	public String shortReadOfIndex;
	public String willNotStoreEmptyPack;
}
