/*
 * Copyright (C) 2011-2013, Robin Rosenberg <robin.rosenberg@dewire.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.lib;

public interface ReflogEntry {
	String PREFIX_CREATED = "created";
	String PREFIX_FAST_FORWARD = "fast-forward";
	String PREFIX_FORCED_UPDATE = "forced-update";

	ObjectId getOldId();

	ObjectId getNewId();

	PersonIdent getWho();

	String getComment();

	CheckoutEntry parseCheckout();

}
