/*
 * Copyright (C) 2008, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import java.util.Collection;

public interface PostReceiveHook {
	PostReceiveHook NULL = (final ReceivePack rp, final Collection<ReceiveCommand> commands) -> {
	};

	void onPostReceive(ReceivePack rp,
					   Collection<ReceiveCommand> commands);
}
