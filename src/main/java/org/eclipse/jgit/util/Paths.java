/*
 * Copyright (C) 2016, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.util;

import static org.eclipse.jgit.lib.FileMode.TYPE_MASK;
import static org.eclipse.jgit.lib.FileMode.TYPE_TREE;

public class Paths {

	public static int compare(byte[] aPath, int aPos, int aEnd, int aMode,
							  byte[] bPath, int bPos, int bEnd, int bMode) {
		int cmp = coreCompare(
				aPath, aPos, aEnd, aMode,
				bPath, bPos, bEnd, bMode);
		if(cmp == 0) {
			cmp = lastPathChar(aMode) - lastPathChar(bMode);
		}
		return cmp;
	}

	public static int compareSameName(
			byte[] aPath, int aPos, int aEnd,
			byte[] bPath, int bPos, int bEnd, int bMode) {
		return coreCompare(
				aPath, aPos, aEnd, TYPE_TREE,
				bPath, bPos, bEnd, bMode);
	}

	private static int coreCompare(
			byte[] aPath, int aPos, int aEnd, int aMode,
			byte[] bPath, int bPos, int bEnd, int bMode) {
		while(aPos < aEnd && bPos < bEnd) {
			int cmp = (aPath[aPos++] & 0xff) - (bPath[bPos++] & 0xff);
			if(cmp != 0) {
				return cmp;
			}
		}
		if(aPos < aEnd) {
			return (aPath[aPos] & 0xff) - lastPathChar(bMode);
		}
		if(bPos < bEnd) {
			return lastPathChar(aMode) - (bPath[bPos] & 0xff);
		}
		return 0;
	}

	private static int lastPathChar(int mode) {
		if((mode & TYPE_MASK) == TYPE_TREE) {
			return '/';
		}
		return 0;
	}
}
