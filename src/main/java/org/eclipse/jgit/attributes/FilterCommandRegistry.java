/*
 * Copyright (C) 2016, Matthias Sohn <matthias.sohn@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.attributes;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jgit.lib.Repository;

public class FilterCommandRegistry {
	private static final Map<String, FilterCommandFactory> filterCommandRegistry = new ConcurrentHashMap<>();

	public static boolean isRegistered(String filterCommandName) {
		return filterCommandRegistry.containsKey(filterCommandName);
	}

	public static Set<String> getRegisteredFilterCommands() {
		return filterCommandRegistry.keySet();
	}

	public static FilterCommand createFilterCommand(String filterCommandName,
													Repository db, InputStream in, OutputStream out) throws IOException {
		FilterCommandFactory cf = filterCommandRegistry.get(filterCommandName);
		return (cf == null) ? null : cf.create(db, in, out);
	}

}
