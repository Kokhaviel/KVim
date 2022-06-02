/*
 * Copyright (c) 2019, Google LLC  and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;

public interface ConnectivityChecker {

	void checkConnectivity(ConnectivityCheckInfo connectivityCheckInfo, Set<ObjectId> haves, ProgressMonitor pm) throws IOException;

	class ConnectivityCheckInfo {

		private Repository repository;
		private PackParser parser;
		private boolean checkObjects;
		private List<ReceiveCommand> commands;
		private RevWalk walk;

		public Repository getRepository() {
			return repository;
		}

		public void setRepository(Repository repository) {
			this.repository = repository;
		}

		public PackParser getParser() {
			return parser;
		}

		public void setParser(PackParser parser) {
			this.parser = parser;
		}

		public boolean isCheckObjects() {
			return checkObjects;
		}

		public void setCheckObjects(boolean checkObjects) {
			this.checkObjects = checkObjects;
		}

		public List<ReceiveCommand> getCommands() {
			return commands;
		}

		public void setCommands(List<ReceiveCommand> commands) {
			this.commands = commands;
		}

		public void setWalk(RevWalk walk) {
			this.walk = walk;
		}

		public RevWalk getWalk() {
			return walk;
		}
	}
}
