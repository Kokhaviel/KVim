/*
 * Copyright (C) 2009-2010, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport.resolver;

import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.ServiceMayNotContinueException;

public interface RepositoryResolver<C> {
	RepositoryResolver<?> NONE = (Object req, String name) -> {
		throw new RepositoryNotFoundException(name);
	};

	Repository open(C req, String name) throws RepositoryNotFoundException,
			ServiceNotAuthorizedException, ServiceNotEnabledException,
			ServiceMayNotContinueException;
}
