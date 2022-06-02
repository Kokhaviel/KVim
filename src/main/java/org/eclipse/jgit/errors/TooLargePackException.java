/*
 * Copyright (C) 2014, Sasa Zivkov <sasa.zivkov@sap.com>, SAP AG and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.errors;

import org.eclipse.jgit.transport.URIish;

public class TooLargePackException extends TransportException {
	private static final long serialVersionUID = 1L;

	public TooLargePackException(URIish uri, String s) {
		super(uri.setPass(null) + ": " + s);
	}
}
