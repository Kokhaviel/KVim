/*
 * Copyright (C) 2019, Google LLC. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import static java.math.BigInteger.ZERO;
import static java.util.Objects.requireNonNull;
import static org.eclipse.jgit.lib.Constants.OBJ_BLOB;
import static org.eclipse.jgit.lib.Constants.OBJ_COMMIT;
import static org.eclipse.jgit.lib.Constants.OBJ_TAG;
import static org.eclipse.jgit.lib.Constants.OBJ_TREE;
import static org.eclipse.jgit.transport.GitProtocolConstants.OPTION_FILTER;

import java.math.BigInteger;
import java.text.MessageFormat;

import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.errors.PackProtocolException;
import org.eclipse.jgit.internal.JGitText;

public final class FilterSpec {

	static class ObjectTypes {
		static ObjectTypes ALL = allow(OBJ_BLOB, OBJ_TREE, OBJ_COMMIT, OBJ_TAG);

		private final BigInteger val;

		private ObjectTypes(BigInteger val) {
			this.val = requireNonNull(val);
		}

		static ObjectTypes allow(int... types) {
			BigInteger bits = ZERO;
			for(int type : types) {
				bits = bits.setBit(type);
			}
			return new ObjectTypes(bits);
		}

		boolean contains(int type) {
			return val.testBit(type);
		}

		@Override
		public boolean equals(Object obj) {
			if(!(obj instanceof ObjectTypes)) {
				return false;
			}

			ObjectTypes other = (ObjectTypes) obj;
			return other.val.equals(val);
		}

		@Override
		public int hashCode() {
			return val.hashCode();
		}
	}

	private final ObjectTypes types;

	private final long blobLimit;

	private final long treeDepthLimit;

	private FilterSpec(ObjectTypes types, long blobLimit, long treeDepthLimit) {
		this.types = requireNonNull(types);
		this.blobLimit = blobLimit;
		this.treeDepthLimit = treeDepthLimit;
	}

	public static FilterSpec fromFilterLine(String filterLine)
			throws PackProtocolException {
		if(filterLine.equals("blob:none")) {
			return FilterSpec.withObjectTypes(
					ObjectTypes.allow(OBJ_TREE, OBJ_COMMIT, OBJ_TAG));
		} else if(filterLine.startsWith("blob:limit=")) {
			long blobLimit = -1;
			try {
				blobLimit = Long
						.parseLong(filterLine.substring("blob:limit=".length()));
			} catch(NumberFormatException ignored) {
			}
			if(blobLimit >= 0) {
				return FilterSpec.withBlobLimit(blobLimit);
			}
		} else if(filterLine.startsWith("tree:")) {
			long treeDepthLimit = -1;
			try {
				treeDepthLimit = Long
						.parseLong(filterLine.substring("tree:".length()));
			} catch(NumberFormatException ignored) {
			}
			if(treeDepthLimit >= 0) {
				return FilterSpec.withTreeDepthLimit(treeDepthLimit);
			}
		}

		throw new PackProtocolException(
				MessageFormat.format(JGitText.get().invalidFilter, filterLine));
	}

	static FilterSpec withObjectTypes(ObjectTypes types) {
		return new FilterSpec(types, -1, -1);
	}

	static FilterSpec withBlobLimit(long blobLimit) {
		if(blobLimit < 0) {
			throw new IllegalArgumentException(
					"blobLimit cannot be negative: " + blobLimit);
		}
		return new FilterSpec(ObjectTypes.ALL, blobLimit, -1);
	}

	static FilterSpec withTreeDepthLimit(long treeDepthLimit) {
		if(treeDepthLimit < 0) {
			throw new IllegalArgumentException(
					"treeDepthLimit cannot be negative: " + treeDepthLimit);
		}
		return new FilterSpec(ObjectTypes.ALL, -1, treeDepthLimit);
	}

	public static final FilterSpec NO_FILTER = new FilterSpec(ObjectTypes.ALL, -1, -1);

	public boolean allowsType(int type) {
		return types.contains(type);
	}

	public long getBlobLimit() {
		return blobLimit;
	}

	public long getTreeDepthLimit() {
		return treeDepthLimit;
	}

	public boolean isNoOp() {
		return types.equals(ObjectTypes.ALL) && blobLimit == -1 && treeDepthLimit == -1;
	}

	@Nullable
	public String filterLine() {
		if(isNoOp()) {
			return null;
		} else if(types.equals(ObjectTypes.allow(OBJ_TREE, OBJ_COMMIT, OBJ_TAG)) &&
				blobLimit == -1 && treeDepthLimit == -1) {
			return OPTION_FILTER + " blob:none";
		} else if(types.equals(ObjectTypes.ALL) && blobLimit >= 0 && treeDepthLimit == -1) {
			return OPTION_FILTER + " blob:limit=" + blobLimit;
		} else if(types.equals(ObjectTypes.ALL) && blobLimit == -1 && treeDepthLimit >= 0) {
			return OPTION_FILTER + " tree:" + treeDepthLimit;
		} else {
			throw new IllegalStateException();
		}
	}
}
