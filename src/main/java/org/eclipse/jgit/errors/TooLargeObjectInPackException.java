/*
 * Copyright (C) 2011, Sasa Zivkov <sasa.zivkov@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.errors;

import java.text.MessageFormat;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.transport.URIish;

public class TooLargeObjectInPackException extends TransportException {
	private static final long serialVersionUID = 1L;

	public TooLargeObjectInPackException(long objectSize,
			long maxObjectSizeLimit) {
		super(MessageFormat.format(JGitText.get().receivePackObjectTooLarge2,
				objectSize, maxObjectSizeLimit));
	}

	public TooLargeObjectInPackException(URIish uri, String s) {
		super(uri.setPass(null) + ": " + s);
	}
}
