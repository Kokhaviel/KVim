/*
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.revwalk;

import java.io.IOException;

abstract class BlockRevQueue extends AbstractRevQueue {
	protected BlockFreeList free;

	protected BlockRevQueue(boolean firstParent) {
		super(firstParent);
		free = new BlockFreeList();
	}

	BlockRevQueue(Generator s) throws IOException {
		super(s.firstParent);
		free = new BlockFreeList();
		outputType = s.outputType();
		s.shareFreeList(this);
		for(; ; ) {
			final RevCommit c = s.next();
			if(c == null)
				break;
			add(c);
		}
	}

	@Override
	public void shareFreeList(BlockRevQueue q) {
		free = q.free;
	}

	static final class BlockFreeList {
		private Block next;

		Block newBlock() {
			Block b = next;
			if(b == null)
				return new Block();
			next = b.next;
			b.clear();
			return b;
		}

		void freeBlock(Block b) {
			b.next = next;
			next = b;
		}

		void clear() {
			next = null;
		}
	}

	static final class Block {
		static final int BLOCK_SIZE = 256;
		Block next;
		final RevCommit[] commits = new RevCommit[BLOCK_SIZE];
		int headIndex;
		int tailIndex;

		boolean isFull() {
			return tailIndex == BLOCK_SIZE;
		}

		boolean isEmpty() {
			return headIndex == tailIndex;
		}

		boolean canUnpop() {
			return headIndex > 0;
		}

		void add(RevCommit c) {
			commits[tailIndex++] = c;
		}

		void unpop(RevCommit c) {
			commits[--headIndex] = c;
		}

		RevCommit pop() {
			return commits[headIndex++];
		}

		void clear() {
			next = null;
			headIndex = 0;
			tailIndex = 0;
		}

		void resetToMiddle() {
			headIndex = tailIndex = BLOCK_SIZE / 2;
		}

		void resetToEnd() {
			headIndex = tailIndex = BLOCK_SIZE;
		}
	}
}
