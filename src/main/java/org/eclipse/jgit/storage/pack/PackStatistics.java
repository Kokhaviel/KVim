/*
 * Copyright (C) 2015, Google Inc.
 *
 * This program and the accompanying materials are made available
 * under the terms of the Eclipse Distribution License v1.0 which
 * accompanies this distribution, is reproduced below, and is
 * available at http://www.eclipse.org/org/documents/edl-v10.php
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or
 * without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above
 *   copyright notice, this list of conditions and the following
 *   disclaimer in the documentation and/or other materials provided
 *   with the distribution.
 *
 * - Neither the name of the Eclipse Foundation, Inc. nor the
 *   names of its contributors may be used to endorse or promote
 *   products derived from this software without specific prior
 *   written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
 * CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.eclipse.jgit.storage.pack;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.internal.storage.pack.CachedPack;
import org.eclipse.jgit.lib.ObjectId;

import java.text.MessageFormat;
import java.util.List;
import java.util.Set;

import static org.eclipse.jgit.lib.Constants.*;

public class PackStatistics {
	public static class ObjectType {
		public static class Accumulator {
			public long cntObjects;
			public long cntDeltas;
			public long reusedObjects;
			public long reusedDeltas;
			public long bytes;
			public long deltaBytes;
		}

		private final ObjectType.Accumulator objectType;

		public ObjectType(ObjectType.Accumulator accumulator) {
			objectType = accumulator;
		}

		public long getObjects() {
			return objectType.cntObjects;
		}

		public long getBytes() {
			return objectType.bytes;
		}

	}

	public static class Accumulator {
		public long advertised;
		public long wants;
		public long haves;
		public long notAdvertisedWants;
		public long timeNegotiating;
		public Set<ObjectId> interestingObjects;
		public Set<ObjectId> uninterestingObjects;
		public Set<ObjectId> clientShallowCommits;
		public List<CachedPack> reusedPacks;
		public Set<ObjectId> rootCommits;
		public int depth;
		public int deltaSearchNonEdgeObjects;
		public int deltasFound;
		public long totalObjects;
		public long bitmapIndexMisses;
		public long totalDeltas;
		public long reusedObjects;
		public long reusedDeltas;
		public long totalBytes;
		public long thinPackBytes;
		public long timeCounting;
		public long timeSearchingForReuse;
		public long timeSearchingForSizes;
		public long timeCompressing;
		public long timeWriting;
		public long reachabilityCheckDuration;
		public long treesTraversed;
		public long offloadedPackfiles;
		public long offloadedPackfileSize;

		public ObjectType.Accumulator[] objectTypes;

		{
			objectTypes = new ObjectType.Accumulator[5];
			objectTypes[OBJ_COMMIT] = new ObjectType.Accumulator();
			objectTypes[OBJ_TREE] = new ObjectType.Accumulator();
			objectTypes[OBJ_BLOB] = new ObjectType.Accumulator();
			objectTypes[OBJ_TAG] = new ObjectType.Accumulator();
		}
	}

	private final Accumulator statistics;

	public PackStatistics(Accumulator accumulator) {
		statistics = accumulator;
	}

	public boolean isShallow() {
		return statistics.depth > 0;
	}

	public int getDepth() {
		return statistics.depth;
	}

	public long getTimeWriting() {
		return statistics.timeWriting;
	}

	public String getMessage() {
		return MessageFormat.format(JGitText.get().packWriterStatistics,
				statistics.totalObjects,
				statistics.totalDeltas,
				statistics.reusedObjects,
				statistics.reusedDeltas);
	}

}
