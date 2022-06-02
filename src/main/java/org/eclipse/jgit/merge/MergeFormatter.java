/*
 * Copyright (C) 2009, Christian Halstrick <christian.halstrick@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.merge;

import org.eclipse.jgit.diff.RawText;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.List;

public class MergeFormatter {

	public void formatMerge(OutputStream out, MergeResult<RawText> res, List<String> seqName, Charset charset)
			throws IOException {
		new MergeFormatterPass(out, res, seqName, charset).formatMerge();
	}


}
