/*
 * Copyright (C) 2011-2012, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import java.io.IOException;

public class ServiceMayNotContinueException extends IOException {
	private static final long serialVersionUID = 1L;

	private boolean output;

	public ServiceMayNotContinueException(String msg, Throwable cause) {
		super(msg, cause);
	}

	public boolean isOutput() {
		return output;
	}

	public void setOutput() {
		output = true;
	}
}
