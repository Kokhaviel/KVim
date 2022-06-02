/*
 * Copyright (C) 2018, Google LLC. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.internal.transport.parser;

import org.eclipse.jgit.annotations.NonNull;

import java.util.HashSet;
import java.util.Set;

import static java.util.Arrays.asList;
import static java.util.Collections.emptySet;
import static java.util.Collections.unmodifiableSet;

public final class FirstCommand {
	private final String line;
	private final Set<String> capabilities;

	@NonNull
	public static FirstCommand fromLine(String line) {
		int nul = line.indexOf('\0');
		if(nul < 0) {
			return new FirstCommand(line, emptySet());
		}
		Set<String> opts =
				new HashSet<>(asList(line.substring(nul + 1).split(" ")));
		return new FirstCommand(line.substring(0, nul), unmodifiableSet(opts));
	}

	private FirstCommand(String line, Set<String> capabilities) {
		this.line = line;
		this.capabilities = capabilities;
	}

	@NonNull
	public String getLine() {
		return line;
	}

	@NonNull
	public Set<String> getCapabilities() {
		return capabilities;
	}
}
