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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.jgit.internal.storage.reftable.BlockWriter.compare;
import static org.eclipse.jgit.internal.storage.reftable.ReftableConstants.FILE_BLOCK_TYPE;
import static org.eclipse.jgit.internal.storage.reftable.ReftableConstants.FILE_HEADER_LEN;
import static org.eclipse.jgit.internal.storage.reftable.ReftableConstants.INDEX_BLOCK_TYPE;
import static org.eclipse.jgit.internal.storage.reftable.ReftableConstants.LOG_BLOCK_TYPE;
import static org.eclipse.jgit.internal.storage.reftable.ReftableConstants.LOG_DATA;
import static org.eclipse.jgit.internal.storage.reftable.ReftableConstants.LOG_NONE;
import static org.eclipse.jgit.internal.storage.reftable.ReftableConstants.OBJ_BLOCK_TYPE;
import static org.eclipse.jgit.internal.storage.reftable.ReftableConstants.REF_BLOCK_TYPE;
import static org.eclipse.jgit.internal.storage.reftable.ReftableConstants.VALUE_1ID;
import static org.eclipse.jgit.internal.storage.reftable.ReftableConstants.VALUE_2ID;
import static org.eclipse.jgit.internal.storage.reftable.ReftableConstants.VALUE_NONE;
import static org.eclipse.jgit.internal.storage.reftable.ReftableConstants.VALUE_SYMREF;
import static org.eclipse.jgit.internal.storage.reftable.ReftableConstants.VALUE_TYPE_MASK;
import static org.eclipse.jgit.internal.storage.reftable.ReftableConstants.reverseUpdateIndex;
import static org.eclipse.jgit.lib.Constants.OBJECT_ID_LENGTH;
import static org.eclipse.jgit.lib.Ref.Storage.NEW;
import static org.eclipse.jgit.lib.Ref.Storage.PACKED;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.internal.storage.io.BlockSource;
import org.eclipse.jgit.lib.CheckoutEntry;
import org.eclipse.jgit.lib.InflaterCache;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectIdRef;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.ReflogEntry;
import org.eclipse.jgit.lib.SymbolicRef;
import org.eclipse.jgit.util.LongList;
import org.eclipse.jgit.util.NB;
import org.eclipse.jgit.util.RawParseUtils;

class BlockReader {
	private byte blockType;
	private long endPosition;

	private byte[] buf;
	private int bufLen, ptr, keysStart, keysEnd,
			restartCnt, restartTbl, nameLen, valueType;

	private byte[] nameBuf = new byte[256];

	byte type() {
		return blockType;
	}

	long endPosition() {
		return endPosition;
	}

	boolean next() {
		return ptr < keysEnd;
	}

	void parseKey() {
		int pfx = readVarint32();
		valueType = readVarint32();
		int sfx = valueType >>> 3;
		if(pfx + sfx > nameBuf.length) {
			int n = Math.max(pfx + sfx, nameBuf.length * 2);
			nameBuf = Arrays.copyOf(nameBuf, n);
		}
		System.arraycopy(buf, ptr, nameBuf, pfx, sfx);
		ptr += sfx;
		nameLen = pfx + sfx;
	}

	String name() {
		int len = nameLen;
		if(blockType == LOG_BLOCK_TYPE) {
			len -= 9;
		}
		return RawParseUtils.decode(UTF_8, nameBuf, 0, len);
	}

	boolean match(byte[] match, boolean matchIsPrefix) {
		int len = nameLen;
		if(blockType == LOG_BLOCK_TYPE) {
			len -= 9;
		}
		if(matchIsPrefix) {
			return len >= match.length
					&& compare(
					match, 0, match.length,
					nameBuf, 0, match.length) == 0;
		}
		return compare(match, 0, match.length, nameBuf, 0, len) == 0;
	}

	long readPositionFromIndex() throws IOException {
		if(blockType != INDEX_BLOCK_TYPE) {
			throw invalidBlock();
		}

		readVarint32();
		int n = readVarint32() >>> 3;
		ptr += n;
		return readVarint64();
	}

	long readUpdateIndexDelta() {
		return readVarint64();
	}

	Ref readRef(long minUpdateIndex) throws IOException {
		long updateIndex = minUpdateIndex + readUpdateIndexDelta();
		String name = RawParseUtils.decode(UTF_8, nameBuf, 0, nameLen);
		switch(valueType & VALUE_TYPE_MASK) {
			case VALUE_NONE:
				return newRef(name, updateIndex);

			case VALUE_1ID:
				return new ObjectIdRef.PeeledNonTag(PACKED, name, readValueId(),
						updateIndex);

			case VALUE_2ID: {
				ObjectId id1 = readValueId();
				ObjectId id2 = readValueId();
				return new ObjectIdRef.PeeledTag(PACKED, name, id1, id2,
						updateIndex);
			}

			case VALUE_SYMREF: {
				String val = readValueString();
				return new SymbolicRef(name, newRef(val, updateIndex), updateIndex);
			}

			default:
				throw invalidBlock();
		}
	}

	@Nullable
	LongList readBlockPositionList() {
		int n = valueType & VALUE_TYPE_MASK;
		if(n == 0) {
			n = readVarint32();
			if(n == 0) {
				return null;
			}
		}

		LongList b = new LongList(n);
		b.add(readVarint64());
		for(int j = 1; j < n; j++) {
			long prior = b.get(j - 1);
			b.add(prior + readVarint64());
		}
		return b;
	}

	long readLogUpdateIndex() {
		return reverseUpdateIndex(NB.decodeUInt64(nameBuf, nameLen - 8));
	}

	@Nullable
	ReflogEntry readLogEntry() {
		if((valueType & VALUE_TYPE_MASK) == LOG_NONE) {
			return null;
		}

		ObjectId oldId = readValueId();
		ObjectId newId = readValueId();
		PersonIdent who = readPersonIdent();
		String msg = readValueString();

		return new ReflogEntry() {
			@Override
			public ObjectId getOldId() {
				return oldId;
			}

			@Override
			public ObjectId getNewId() {
				return newId;
			}

			@Override
			public PersonIdent getWho() {
				return who;
			}

			@Override
			public String getComment() {
				return msg;
			}

			@Override
			public CheckoutEntry parseCheckout() {
				return null;
			}
		};
	}

	private ObjectId readValueId() {
		ObjectId id = ObjectId.fromRaw(buf, ptr);
		ptr += OBJECT_ID_LENGTH;
		return id;
	}

	private String readValueString() {
		int len = readVarint32();
		int end = ptr + len;
		String s = RawParseUtils.decode(UTF_8, buf, ptr, end);
		ptr = end;
		return s;
	}

	private PersonIdent readPersonIdent() {
		String name = readValueString();
		String email = readValueString();
		long ms = readVarint64() * 1000;
		int tz = readInt16();
		return new PersonIdent(name, email, ms, tz);
	}

	void readBlock(BlockSource src, long pos, int fileBlockSize)
			throws IOException {
		readBlockIntoBuf(src, pos, fileBlockSize);
		parseBlockStart(src, pos, fileBlockSize);
	}

	private void readBlockIntoBuf(BlockSource src, long pos, int size)
			throws IOException {
		ByteBuffer b = src.read(pos, size);
		bufLen = b.position();
		if(bufLen <= 0) {
			throw invalidBlock();
		}
		if(b.hasArray() && b.arrayOffset() == 0) {
			buf = b.array();
		} else {
			buf = new byte[bufLen];
			b.flip();
			b.get(buf);
		}
		endPosition = pos + bufLen;
	}

	private void parseBlockStart(BlockSource src, long pos, int fileBlockSize)
			throws IOException {
		ptr = 0;
		if(pos == 0) {
			if(bufLen == FILE_HEADER_LEN) {
				setupEmptyFileBlock();
				return;
			}
			ptr += FILE_HEADER_LEN;
		}

		int typeAndSize = NB.decodeInt32(buf, ptr);
		ptr += 4;

		blockType = (byte) (typeAndSize >>> 24);
		int blockLen = decodeBlockLen(typeAndSize);
		if(blockType == LOG_BLOCK_TYPE) {
			long deflatedSize = inflateBuf(src, pos, blockLen, fileBlockSize);
			endPosition = pos + 4 + deflatedSize;
		} else if(bufLen < blockLen) {
			readBlockIntoBuf(src, pos, blockLen);
		} else if(bufLen > blockLen) {
			bufLen = blockLen;
		}

		if(blockType != FILE_BLOCK_TYPE) {
			restartCnt = NB.decodeUInt16(buf, bufLen - 2);
			restartTbl = bufLen - (restartCnt * 3 + 2);
			keysStart = ptr;
			keysEnd = restartTbl;
		} else {
			keysStart = ptr;
			keysEnd = ptr;
		}
	}

	static int decodeBlockLen(int typeAndSize) {
		return typeAndSize & 0xffffff;
	}

	private long inflateBuf(BlockSource src, long pos, int blockLen,
							int fileBlockSize) throws IOException {
		byte[] dst = new byte[blockLen];
		System.arraycopy(buf, 0, dst, 0, 4);

		long deflatedSize;
		Inflater inf = InflaterCache.get();
		try {
			inf.setInput(buf, ptr, bufLen - ptr);
			for(int o = 4; ; ) {
				int n = inf.inflate(dst, o, dst.length - o);
				o += n;
				if(inf.finished()) {
					deflatedSize = inf.getBytesRead();
					break;
				} else if(n <= 0 && inf.needsInput()) {
					long p = pos + 4 + inf.getBytesRead();
					readBlockIntoBuf(src, p, fileBlockSize);
					inf.setInput(buf, 0, bufLen);
				} else if(n <= 0) {
					throw invalidBlock();
				}
			}
		} catch(DataFormatException e) {
			throw invalidBlock(e);
		} finally {
			InflaterCache.release(inf);
		}

		buf = dst;
		bufLen = dst.length;
		return deflatedSize;
	}

	private void setupEmptyFileBlock() {
		blockType = FILE_BLOCK_TYPE;
		ptr = FILE_HEADER_LEN;
		restartCnt = 0;
		restartTbl = bufLen;
		keysStart = bufLen;
		keysEnd = bufLen;
	}

	void verifyIndex() throws IOException {
		if(blockType != INDEX_BLOCK_TYPE) {
			throw invalidBlock();
		}
	}

	int seekKey(byte[] key) {
		int low = 0;
		int end = restartCnt;
		for(; ; ) {
			int mid = (low + end) >>> 1;
			int p = NB.decodeUInt24(buf, restartTbl + mid * 3);
			ptr = p + 1;
			int n = readVarint32() >>> 3;
			int cmp = compare(key, 0, key.length, buf, ptr, n);
			if(cmp < 0) {
				end = mid;
			} else if(cmp == 0) {
				ptr = p;
				return 0;
			} else {
				low = mid + 1;
			}
			if(low >= end) {
				return scanToKey(key, p, low, cmp);
			}
		}
	}

	private int scanToKey(byte[] key, int rPtr, int rIdx, int rCmp) {
		if(rCmp < 0) {
			if(rIdx == 0) {
				ptr = keysStart;
				return -1;
			}
			ptr = NB.decodeUInt24(buf, restartTbl + (rIdx - 1) * 3);
		} else {
			ptr = rPtr;
		}

		int cmp;
		do {
			int savePtr = ptr;
			parseKey();
			cmp = compare(key, 0, key.length, nameBuf, 0, nameLen);
			if(cmp <= 0) {
				ptr = savePtr;
				return cmp < 0 && savePtr == keysStart ? -1 : 0;
			}
			skipValue();
		} while(ptr < keysEnd);
		return cmp;
	}

	void skipValue() {
		switch(blockType) {
			case REF_BLOCK_TYPE:
				readVarint64();
				switch(valueType & VALUE_TYPE_MASK) {
					case VALUE_NONE:
						return;
					case VALUE_1ID:
						ptr += OBJECT_ID_LENGTH;
						return;
					case VALUE_2ID:
						ptr += 2 * OBJECT_ID_LENGTH;
						return;
					case VALUE_SYMREF:
						skipString();
						return;
				}
				break;
			case OBJ_BLOCK_TYPE: {
				int n = valueType & VALUE_TYPE_MASK;
				if(n == 0) {
					n = readVarint32();
				}
				while(n-- > 0) {
					readVarint32();
				}
				return;
			}
			case INDEX_BLOCK_TYPE:
				readVarint32();
				return;
			case LOG_BLOCK_TYPE:
				if((valueType & VALUE_TYPE_MASK) == LOG_NONE) {
					return;
				} else if((valueType & VALUE_TYPE_MASK) == LOG_DATA) {
					ptr += 2 * OBJECT_ID_LENGTH;
					skipString();
					skipString();
					readVarint64();
					ptr += 2;
					skipString();
					return;
				}
		}
		throw new IllegalStateException();
	}

	private void skipString() {
		int n = readVarint32();
		ptr += n;
	}

	private short readInt16() {
		short result = (short) NB.decodeUInt16(buf, ptr);
		ptr += 2;
		return result;
	}

	private int readVarint32() {
		byte c = buf[ptr++];
		int val = c & 0x7f;
		while((c & 0x80) != 0) {
			c = buf[ptr++];
			val++;
			val <<= 7;
			val |= (c & 0x7f);
		}
		return val;
	}

	private long readVarint64() {
		byte c = buf[ptr++];
		long val = c & 0x7f;
		while((c & 0x80) != 0) {
			c = buf[ptr++];
			val++;
			val <<= 7;
			val |= (c & 0x7f);
		}
		return val;
	}

	private static Ref newRef(String name, long updateIndex) {
		return new ObjectIdRef.Unpeeled(NEW, name, null, updateIndex);
	}

	private static IOException invalidBlock() {
		return invalidBlock(null);
	}

	private static IOException invalidBlock(Throwable cause) {
		return new IOException(JGitText.get().invalidReftableBlock, cause);
	}
}
