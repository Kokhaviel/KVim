/*
 * Copyright (C) 2010, Mathias Kinzler <mathias.kinzler@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.api;

import org.eclipse.jgit.transport.FetchResult;

public class PullResult {
	private final FetchResult fetchResult;
	private final MergeResult mergeResult;
	private final RebaseResult rebaseResult;

	PullResult(FetchResult fetchResult, MergeResult mergeResult) {
		this.fetchResult = fetchResult;
		this.mergeResult = mergeResult;
		this.rebaseResult = null;
	}

	PullResult(FetchResult fetchResult, RebaseResult rebaseResult) {
		this.fetchResult = fetchResult;
		this.mergeResult = null;
		this.rebaseResult = rebaseResult;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		if(fetchResult != null)
			sb.append(fetchResult);
		else
			sb.append("No fetch result");
		sb.append("\n");
		if(mergeResult != null)
			sb.append(mergeResult);
		else if(rebaseResult != null)
			sb.append(rebaseResult);
		else
			sb.append("No update result");
		return sb.toString();
	}
}
