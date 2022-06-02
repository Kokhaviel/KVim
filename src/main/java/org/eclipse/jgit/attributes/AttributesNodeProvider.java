/*
 * Copyright (C) 2014, Arthur Daussy <arthur.daussy@obeo.fr> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.attributes;

import java.io.IOException;

public interface AttributesNodeProvider {

	AttributesNode getInfoAttributesNode() throws IOException;

	AttributesNode getGlobalAttributesNode() throws IOException;

}
