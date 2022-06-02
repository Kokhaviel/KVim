/*
 * Copyright (C) 2017, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.reftable;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jgit.internal.storage.reftable.ReftableWriter.Stats;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.ReflogEntry;

public class ReftableCompactor {
	private final ReftableWriter writer;
	private final ArrayDeque<ReftableReader> tables = new ArrayDeque<>();

	private boolean includeDeletes;
	private long reflogExpireOldestReflogTimeMillis;
	private Stats stats;

	public ReftableCompactor(OutputStream out) {
		writer = new ReftableWriter(out);
	}

	public ReftableCompactor setConfig(ReftableConfig cfg) {
		writer.setConfig(cfg);
		return this;
	}

	public ReftableCompactor setIncludeDeletes(boolean deletes) {
		includeDeletes = deletes;
		return this;
	}

	public void addAll(List<ReftableReader> readers) throws IOException {
		tables.addAll(readers);
	}

	public void compact() throws IOException {
		MergedReftable mr = new MergedReftable(new ArrayList<>(tables));
		mr.setIncludeDeletes(includeDeletes);

		writer.setMaxUpdateIndex(mr.maxUpdateIndex());
		writer.setMinUpdateIndex(mr.minUpdateIndex());

		writer.begin();
		mergeRefs(mr);
		mergeLogs(mr);
		writer.finish();
		stats = writer.getStats();
	}

	public Stats getStats() {
		return stats;
	}

	private void mergeRefs(MergedReftable mr) throws IOException {
		try(RefCursor rc = mr.allRefs()) {
			while(rc.next()) {
				writer.writeRef(rc.getRef(), rc.getRef().getUpdateIndex());
			}
		}
	}

	private void mergeLogs(MergedReftable mr) throws IOException {
		if(reflogExpireOldestReflogTimeMillis == Long.MAX_VALUE) {
			return;
		}

		try(LogCursor lc = mr.allLogs()) {
			while(lc.next()) {
				long updateIndex = lc.getUpdateIndex();
				long reflogExpireMinUpdateIndex = 0;
				if(updateIndex < reflogExpireMinUpdateIndex) {
					continue;
				}

				String refName = lc.getRefName();
				ReflogEntry log = lc.getReflogEntry();
				if(log == null) {
					if(includeDeletes) {
						writer.deleteLog(refName, updateIndex);
					}
					continue;
				}

				PersonIdent who = log.getWho();
				if(who.getWhen().getTime() >= reflogExpireOldestReflogTimeMillis) {
					writer.writeLog(
							refName,
							updateIndex,
							who,
							log.getOldId(),
							log.getNewId(),
							log.getComment());
				}
			}
		}
	}
}
