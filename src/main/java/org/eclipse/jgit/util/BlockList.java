/*
 * Copyright (C) 2011, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.util;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;

public class BlockList<T> extends AbstractList<T> {
	private static final int BLOCK_BITS = 10;

	static final int BLOCK_SIZE = 1 << BLOCK_BITS;

	private static final int BLOCK_MASK = BLOCK_SIZE - 1;

	T[][] directory;

	int size;

	private int tailDirIdx;

	private int tailBlkIdx;

	private T[] tailBlock;

	public BlockList() {
		directory = BlockList.newDirectory(256);
		directory[0] = BlockList.newBlock();
		tailBlock = directory[0];
	}

	public BlockList(int capacity) {
		int dirSize = toDirectoryIndex(capacity);
		if((capacity & BLOCK_MASK) != 0 || dirSize == 0)
			dirSize++;
		directory = BlockList.newDirectory(dirSize);
		directory[0] = BlockList.newBlock();
		tailBlock = directory[0];
	}

	@Override
	public int size() {
		return size;
	}

	@Override
	public void clear() {
		for(T[] block : directory) {
			if(block != null)
				Arrays.fill(block, null);
		}
		size = 0;
		tailDirIdx = 0;
		tailBlkIdx = 0;
		tailBlock = directory[0];
	}

	@Override
	public T get(int index) {
		if(index < 0 || size <= index)
			throw new IndexOutOfBoundsException(String.valueOf(index));
		return directory[toDirectoryIndex(index)][toBlockIndex(index)];
	}

	@Override
	public T set(int index, T element) {
		if(index < 0 || size <= index)
			throw new IndexOutOfBoundsException(String.valueOf(index));
		T[] blockRef = directory[toDirectoryIndex(index)];
		int blockIdx = toBlockIndex(index);
		T old = blockRef[blockIdx];
		blockRef[blockIdx] = element;
		return old;
	}

	public void addAll(BlockList<T> src) {
		if(src.size == 0)
			return;

		int srcDirIdx = 0;
		for(; srcDirIdx < src.tailDirIdx; srcDirIdx++)
			addAll(src.directory[srcDirIdx], 0, BLOCK_SIZE);
		if(src.tailBlkIdx != 0)
			addAll(src.tailBlock, 0, src.tailBlkIdx);
	}

	public void addAll(T[] src, int srcIdx, int srcCnt) {
		while(0 < srcCnt) {
			int i = tailBlkIdx;
			int n = Math.min(srcCnt, BLOCK_SIZE - i);
			if(n == 0) {
				add(src[srcIdx++]);
				srcCnt--;
				continue;
			}

			System.arraycopy(src, srcIdx, tailBlock, i, n);
			tailBlkIdx += n;
			size += n;
			srcIdx += n;
			srcCnt -= n;
		}
	}

	@Override
	public boolean add(T element) {
		int i = tailBlkIdx;
		if(i < BLOCK_SIZE) {
			tailBlock[i] = element;
			tailBlkIdx = i + 1;
			size++;
			return true;
		}

		if(++tailDirIdx == directory.length) {
			T[][] newDir = BlockList.newDirectory(directory.length << 1);
			System.arraycopy(directory, 0, newDir, 0, directory.length);
			directory = newDir;
		}

		T[] blockRef = directory[tailDirIdx];
		if(blockRef == null) {
			blockRef = BlockList.newBlock();
			directory[tailDirIdx] = blockRef;
		}
		blockRef[0] = element;
		tailBlock = blockRef;
		tailBlkIdx = 1;
		size++;
		return true;
	}

	@Override
	public void add(int index, T element) {
		if(index == size) {
			add(element);

		} else if(index < 0 || size < index) {
			throw new IndexOutOfBoundsException(String.valueOf(index));

		} else {
			add(null);
			for(int oldIdx = size - 2; index <= oldIdx; oldIdx--)
				set(oldIdx + 1, get(oldIdx));
			set(index, element);
		}
	}

	@Override
	public T remove(int index) {
		if(index == size - 1) {
			T[] blockRef = directory[toDirectoryIndex(index)];
			int blockIdx = toBlockIndex(index);
			T old = blockRef[blockIdx];
			blockRef[blockIdx] = null;
			size--;
			if(0 < tailBlkIdx)
				tailBlkIdx--;
			else
				resetTailBlock();
			return old;

		} else if(index < 0 || size <= index) {
			throw new IndexOutOfBoundsException(String.valueOf(index));

		} else {
			T old = get(index);
			for(; index < size - 1; index++)
				set(index, get(index + 1));
			set(size - 1, null);
			size--;
			resetTailBlock();
			return old;
		}
	}

	private void resetTailBlock() {
		tailDirIdx = toDirectoryIndex(size);
		tailBlkIdx = toBlockIndex(size);
		tailBlock = directory[tailDirIdx];
	}

	@Override
	public Iterator<T> iterator() {
		return new MyIterator();
	}

	static int toDirectoryIndex(int index) {
		return index >>> BLOCK_BITS;
	}

	static int toBlockIndex(int index) {
		return index & BLOCK_MASK;
	}

	@SuppressWarnings("unchecked")
	private static <T> T[][] newDirectory(int size) {
		return (T[][]) new Object[size][];
	}

	@SuppressWarnings("unchecked")
	private static <T> T[] newBlock() {
		return (T[]) new Object[BLOCK_SIZE];
	}

	private class MyIterator implements Iterator<T> {
		private int index;

		private int dirIdx;

		private int blkIdx;

		private T[] block = directory[0];

		@Override
		public boolean hasNext() {
			return index < size;
		}

		@Override
		public T next() {
			if(size <= index)
				throw new NoSuchElementException();

			T res = block[blkIdx];
			if(++blkIdx == BLOCK_SIZE) {
				if(++dirIdx < directory.length)
					block = directory[dirIdx];
				else
					block = null;
				blkIdx = 0;
			}
			index++;
			return res;
		}

		@Override
		public void remove() {
			if(index == 0)
				throw new IllegalStateException();

			BlockList.this.remove(--index);

			dirIdx = toDirectoryIndex(index);
			blkIdx = toBlockIndex(index);
			block = directory[dirIdx];
		}
	}
}
