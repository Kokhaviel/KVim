/*
 * Copyright (C) 2011, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import java.util.Collection;

import org.eclipse.jgit.lib.ObjectId;

public interface PreUploadHook {

	PreUploadHook NULL = new PreUploadHook() {
		@Override
		public void onBeginNegotiateRound(UploadPack up, Collection<? extends ObjectId> wants, int cntOffered) {
		}

		@Override
		public void onEndNegotiateRound(UploadPack up, Collection<? extends ObjectId> wants, int cntCommon,
										int cntNotFound, boolean ready) {
		}

		@Override
		public void onSendPack(UploadPack up, Collection<? extends ObjectId> wants,
							   Collection<? extends ObjectId> haves) {
		}
	};

	void onBeginNegotiateRound(UploadPack up, Collection<? extends ObjectId> wants, int cntOffered)
			throws ServiceMayNotContinueException;

	void onEndNegotiateRound(UploadPack up, Collection<? extends ObjectId> wants, int cntCommon,
							 int cntNotFound, boolean ready)
			throws ServiceMayNotContinueException;

	void onSendPack(UploadPack up, Collection<? extends ObjectId> wants, Collection<? extends ObjectId> haves)
			throws ServiceMayNotContinueException;
}
