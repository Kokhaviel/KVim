/*
 * Copyright (C) 2007, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2006-2007, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.pack;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.util.*;

public class BinaryDelta {

	public static long getResultSize(byte[] delta) {
		int p = 0, c;
		do {
			c = delta[p++] & 0xff;
		} while((c & 0x80) != 0);

		long resLen = 0;
		int shift = 0;
		do {
			c = delta[p++] & 0xff;
			resLen |= ((long) (c & 0x7f)) << shift;
			shift += 7;
		} while((c & 0x80) != 0);
		return resLen;
	}

	public static byte[] apply(byte[] base, byte[] delta) {
		return apply(base, delta, null);
	}

	public static byte[] apply(final byte[] base, final byte[] delta, byte[] result) {
		int deltaPtr = 0;
		int baseLen = 0;
		int c, shift = 0;
		do {
			c = delta[deltaPtr++] & 0xff;
			baseLen |= (c & 0x7f) << shift;
			shift += 7;
		} while((c & 0x80) != 0);
		if(base.length != baseLen) throw new IllegalArgumentException(JGitText.get().baseLengthIncorrect);

		int resLen = 0;
		shift = 0;
		do {
			c = delta[deltaPtr++] & 0xff;
			resLen |= (c & 0x7f) << shift;
			shift += 7;
		} while((c & 0x80) != 0);

		if(result == null) result = new byte[resLen];
		else if(result.length != resLen) throw new IllegalArgumentException(JGitText.get().resultLengthIncorrect);

		int resultPtr = 0;
		while(deltaPtr < delta.length) {
			final int cmd = delta[deltaPtr++] & 0xff;
			if((cmd & 0x80) != 0) {
				int copyOffset = 0;
				if((cmd & 0x01) != 0) copyOffset = delta[deltaPtr++] & 0xff;
				if((cmd & 0x02) != 0) copyOffset |= (delta[deltaPtr++] & 0xff) << 8;
				if((cmd & 0x04) != 0) copyOffset |= (delta[deltaPtr++] & 0xff) << 16;
				if((cmd & 0x08) != 0) copyOffset |= (delta[deltaPtr++] & 0xff) << 24;

				int copySize = 0;
				if((cmd & 0x10) != 0) copySize = delta[deltaPtr++] & 0xff;
				if((cmd & 0x20) != 0) copySize |= (delta[deltaPtr++] & 0xff) << 8;
				if((cmd & 0x40) != 0) copySize |= (delta[deltaPtr++] & 0xff) << 16;
				if(copySize == 0) copySize = 0x10000;

				System.arraycopy(base, copyOffset, result, resultPtr, copySize);
				resultPtr += copySize;
			} else if(cmd != 0) {
				System.arraycopy(delta, deltaPtr, result, resultPtr, cmd);
				deltaPtr += cmd;
				resultPtr += cmd;
			} else {
				throw new IllegalArgumentException(JGitText.get().unsupportedCommand0);
			}
		}
		return result;
	}

	public static String format(byte[] delta) {
		return format(delta, true);
	}

	public static String format(byte[] delta, boolean includeHeader) {
		StringBuilder r = new StringBuilder();
		int deltaPtr = 0;
		long baseLen = 0;
		int c, shift = 0;
		do {
			c = delta[deltaPtr++] & 0xff;
			baseLen |= ((long) (c & 0x7f)) << shift;
			shift += 7;
		} while((c & 0x80) != 0);

		long resLen = 0;
		shift = 0;
		do {
			c = delta[deltaPtr++] & 0xff;
			resLen |= ((long) (c & 0x7f)) << shift;
			shift += 7;
		} while((c & 0x80) != 0);

		if(includeHeader) r.append("DELTA( BASE=").append(baseLen).append(" RESULT=").append(resLen).append(" )\n");

		while(deltaPtr < delta.length) {
			final int cmd = delta[deltaPtr++] & 0xff;
			if((cmd & 0x80) != 0) {
				int copyOffset = 0;
				if((cmd & 0x01) != 0) copyOffset = delta[deltaPtr++] & 0xff;
				if((cmd & 0x02) != 0) copyOffset |= (delta[deltaPtr++] & 0xff) << 8;
				if((cmd & 0x04) != 0) copyOffset |= (delta[deltaPtr++] & 0xff) << 16;
				if((cmd & 0x08) != 0) copyOffset |= (delta[deltaPtr++] & 0xff) << 24;

				int copySize = 0;
				if((cmd & 0x10) != 0) copySize = delta[deltaPtr++] & 0xff;
				if((cmd & 0x20) != 0) copySize |= (delta[deltaPtr++] & 0xff) << 8;
				if((cmd & 0x40) != 0) copySize |= (delta[deltaPtr++] & 0xff) << 16;
				if(copySize == 0) copySize = 0x10000;

				r.append("  COPY  (").append(copyOffset).append(", ").append(copySize).append(")\n");
			} else if(cmd != 0) {
				r.append("  INSERT(").append(QuotedString.GIT_PATH.quote(
						RawParseUtils.decode(delta, deltaPtr, deltaPtr + cmd))).append(")\n");
				deltaPtr += cmd;
			} else throw new IllegalArgumentException(JGitText.get().unsupportedCommand0);
		}
		return r.toString();
	}
}
