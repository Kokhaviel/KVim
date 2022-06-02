/*
 * Copyright (C) 2016, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import org.eclipse.jgit.lib.Constants;

public class ReceivedPackStatistics {

	public static class Builder {

		public void setNumBytesRead() {
		}

		void incrementNumBytesDuplicated() {
		}

		public void addWholeObject(int type) {
			switch(type) {
				case Constants.OBJ_COMMIT:
				case Constants.OBJ_TREE:
				case Constants.OBJ_BLOB:
				case Constants.OBJ_TAG:
					break;
				default:
					throw new IllegalArgumentException(
							type + " cannot be a whole object");
			}
		}

		public void addOffsetDelta() {
		}

		public void addRefDelta() {
		}

		void incrementObjectsDuplicated() {
		}

		public void addDeltaObject(int type) {
			switch(type) {
				case Constants.OBJ_COMMIT:
				case Constants.OBJ_TREE:
				case Constants.OBJ_BLOB:
				case Constants.OBJ_TAG:
					break;
				default:
					throw new IllegalArgumentException(
							"delta should be a delta to a whole object. " +
									type + " cannot be a whole object");
			}
		}

		ReceivedPackStatistics build() {
			return new ReceivedPackStatistics();
		}
	}
}
