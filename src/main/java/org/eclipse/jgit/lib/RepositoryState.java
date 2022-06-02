/*
 * Copyright (C) 2008, Mike Ralphson <mike@abacus.co.uk>
 * Copyright (C) 2008, Robin Rosenberg <robin.rosenberg.lists@dewire.com>
 * Copyright (C) 2007, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.lib;

public enum RepositoryState {
	BARE {
		@Override
		public boolean canCommit() {
			return false;
		}

	},

	SAFE {
		@Override
		public boolean canCommit() {
			return true;
		}

	},

	MERGING {
		@Override
		public boolean canCommit() {
			return false;
		}

	},

	MERGING_RESOLVED {
		@Override
		public boolean canCommit() {
			return true;
		}

	},

	CHERRY_PICKING {
		@Override
		public boolean canCommit() {
			return false;
		}

	},

	CHERRY_PICKING_RESOLVED {
		@Override
		public boolean canCommit() {
			return true;
		}

	},

	REVERTING {
		@Override
		public boolean canCommit() {
			return false;
		}

	},

	REVERTING_RESOLVED {
		@Override
		public boolean canCommit() {
			return true;
		}

	},

	REBASING {
		@Override
		public boolean canCommit() {
			return true;
		}

	},

	REBASING_REBASING {
		@Override
		public boolean canCommit() {
			return true;
		}

	},

	APPLY {
		@Override
		public boolean canCommit() {
			return true;
		}

	},

	REBASING_MERGE {
		@Override
		public boolean canCommit() {
			return true;
		}

	},

	REBASING_INTERACTIVE {
		@Override
		public boolean canCommit() {
			return true;
		}

	},

	BISECTING {
		@Override
		public boolean canCommit() {
			return true;
		}

	};

	public abstract boolean canCommit();

}
