/*
 * Copyright (C) 2017, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.revwalk;

import org.eclipse.jgit.lib.BitmapIndex.Bitmap;
import org.eclipse.jgit.lib.BitmapIndex.BitmapBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevFlag;

public class AddToBitmapFilter extends RevFilter {
	private final BitmapBuilder bitmap;

	public AddToBitmapFilter(BitmapBuilder bitmap) {
		this.bitmap = bitmap;
	}

	@Override
	public final boolean include(RevWalk walker, RevCommit cmit) {
		Bitmap visitedBitmap;

		if(bitmap.contains(cmit)) {
		} else if((visitedBitmap = bitmap.getBitmapIndex()
				.getBitmap(cmit)) != null) {
			bitmap.or(visitedBitmap);
		} else {
			bitmap.addObject(cmit, Constants.OBJ_COMMIT);
			return true;
		}

		for(RevCommit p : cmit.getParents()) {
			p.add(RevFlag.SEEN);
		}
		return false;
	}

	@Override
	public final RevFilter clone() {
		throw new UnsupportedOperationException();
	}

	@Override
	public final boolean requiresCommitBody() {
		return false;
	}
}
