/*
 * Copyright (C) 2008-2009, Google Inc.
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.util;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ProgressMonitor;

import java.io.*;
import java.nio.file.Files;
import java.util.ArrayList;

public abstract class TemporaryBuffer extends OutputStream {
	protected static final int DEFAULT_IN_CORE_LIMIT = 1024 * 1024;

	ArrayList<Block> blocks;
	private final int inCoreLimit;
	private final int initialBlocks;
	private OutputStream overflow;

	protected TemporaryBuffer(int limit) {
		this(limit, limit);
	}

	protected TemporaryBuffer(int estimatedSize, int limit) {
		if(estimatedSize > limit)
			throw new IllegalArgumentException();
		this.inCoreLimit = limit;
		this.initialBlocks = (estimatedSize - 1) / Block.SZ + 1;
		reset();
	}

	@Override
	public void write(int b) throws IOException {
		if(overflow != null) {
			overflow.write(b);
			return;
		}

		Block s = last();
		if(s.isFull()) {
			if(reachedInCoreLimit()) {
				overflow.write(b);
				return;
			}

			s = new Block();
			blocks.add(s);
		}
		s.buffer[s.count++] = (byte) b;
	}

	@Override
	public void write(byte[] b, int off, int len) throws IOException {
		if(overflow == null) {
			while(len > 0) {
				Block s = last();
				if(s.isFull()) {
					if(reachedInCoreLimit())
						break;

					s = new Block();
					blocks.add(s);
				}

				final int n = Math.min(s.buffer.length - s.count, len);
				System.arraycopy(b, off, s.buffer, s.count, n);
				s.count += n;
				len -= n;
				off += n;
			}
		}

		if(len > 0)
			overflow.write(b, off, len);
	}

	public void copy(InputStream in) throws IOException {
		if(blocks != null) {
			for(; ; ) {
				Block s = last();
				if(s.isFull()) {
					if(reachedInCoreLimit())
						break;
					s = new Block();
					blocks.add(s);
				}

				int n = in.read(s.buffer, s.count, s.buffer.length - s.count);
				if(n < 1)
					return;
				s.count += n;
			}
		}

		final byte[] tmp = new byte[Block.SZ];
		int n;
		while((n = in.read(tmp)) > 0)
			overflow.write(tmp, 0, n);
	}

	public long length() {
		return inCoreLength();
	}

	private long inCoreLength() {
		final Block last = last();
		return ((long) blocks.size() - 1) * Block.SZ + last.count;
	}

	public byte[] toByteArray() throws IOException {
		final long len = length();
		if(Integer.MAX_VALUE < len)
			throw new OutOfMemoryError(JGitText.get().lengthExceedsMaximumArraySize);
		final byte[] out = new byte[(int) len];
		int outPtr = 0;
		for(Block b : blocks) {
			System.arraycopy(b.buffer, 0, out, outPtr, b.count);
			outPtr += b.count;
		}
		return out;
	}

	public String toString(int limit) {
		try {
			return RawParseUtils.decode(toByteArray(limit));
		} catch(IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public byte[] toByteArray(int limit) throws IOException {
		final long len = Math.min(length(), limit);
		int length = (int) len;
		final byte[] out = new byte[length];
		int outPtr = 0;
		for(Block b : blocks) {
			int toCopy = Math.min(length - outPtr, b.count);
			System.arraycopy(b.buffer, 0, out, outPtr, toCopy);
			outPtr += toCopy;
			if(outPtr == length) {
				break;
			}
		}
		return out;
	}

	public void writeTo(OutputStream os, ProgressMonitor pm)
			throws IOException {
		if(pm == null)
			pm = NullProgressMonitor.INSTANCE;
		for(Block b : blocks) {
			os.write(b.buffer, 0, b.count);
			pm.update(b.count / 1024);
		}
	}

	public InputStream openInputStream() throws IOException {
		return new BlockInputStream();
	}

	public InputStream openInputStreamWithAutoDestroy() throws IOException {
		return new BlockInputStream() {
			@Override
			public void close() throws IOException {
				super.close();
				destroy();
			}
		};
	}

	public void reset() {
		if(overflow != null) {
			destroy();
		}
		if(blocks != null)
			blocks.clear();
		else
			blocks = new ArrayList<>(initialBlocks);
		blocks.add(new Block(Math.min(inCoreLimit, Block.SZ)));
	}

	protected abstract OutputStream overflow() throws IOException;

	private Block last() {
		return blocks.get(blocks.size() - 1);
	}

	private boolean reachedInCoreLimit() throws IOException {
		if(inCoreLength() < inCoreLimit)
			return false;

		switchToOverflow();
		return true;
	}

	private void switchToOverflow() throws IOException {
		overflow = overflow();

		final Block last = blocks.remove(blocks.size() - 1);
		for(Block b : blocks)
			overflow.write(b.buffer, 0, b.count);
		blocks = null;

		overflow = new BufferedOutputStream(overflow, Block.SZ);
		overflow.write(last.buffer, 0, last.count);
	}

	@Override
	public void close() throws IOException {
		if(overflow != null) {
			try {
				overflow.close();
			} finally {
				overflow = null;
			}
		}
	}

	public void destroy() {
		blocks = null;

		if(overflow != null) {
			try {
				overflow.close();
			} catch(IOException ignored) {
			} finally {
				overflow = null;
			}
		}
	}

	public static class LocalFile extends TemporaryBuffer {
		private final File directory;

		private File onDiskFile;

		public LocalFile(File directory) {
			this(directory, DEFAULT_IN_CORE_LIMIT);
		}

		public LocalFile(File directory, int inCoreLimit) {
			super(inCoreLimit);
			this.directory = directory;
		}

		@Override
		protected OutputStream overflow() throws IOException {
			onDiskFile = File.createTempFile("jgit_", ".buf", directory);
			return new BufferedOutputStream(Files.newOutputStream(onDiskFile.toPath()));
		}

		@Override
		public long length() {
			if(onDiskFile == null) {
				return super.length();
			}
			return onDiskFile.length();
		}

		@Override
		public byte[] toByteArray() throws IOException {
			if(onDiskFile == null) {
				return super.toByteArray();
			}

			final long len = length();
			if(Integer.MAX_VALUE < len)
				throw new OutOfMemoryError(JGitText.get().lengthExceedsMaximumArraySize);
			final byte[] out = new byte[(int) len];
			try(FileInputStream in = new FileInputStream(onDiskFile)) {
				IO.readFully(in, out, 0, (int) len);
			}
			return out;
		}

		@Override
		public byte[] toByteArray(int limit) throws IOException {
			if(onDiskFile == null) {
				return super.toByteArray(limit);
			}
			final long len = Math.min(length(), limit);
			final byte[] out = new byte[(int) len];
			try(FileInputStream in = new FileInputStream(onDiskFile)) {
				int read = 0;
				int chunk;
				while((chunk = in.read(out, read, out.length - read)) >= 0) {
					read += chunk;
					if(read == out.length) {
						break;
					}
				}
			}
			return out;
		}

		@Override
		public void writeTo(OutputStream os, ProgressMonitor pm)
				throws IOException {
			if(onDiskFile == null) {
				super.writeTo(os, pm);
				return;
			}
			if(pm == null)
				pm = NullProgressMonitor.INSTANCE;
			try(FileInputStream in = new FileInputStream(onDiskFile)) {
				int cnt;
				final byte[] buf = new byte[Block.SZ];
				while((cnt = in.read(buf)) >= 0) {
					os.write(buf, 0, cnt);
					pm.update(cnt / 1024);
				}
			}
		}

		@Override
		public InputStream openInputStream() throws IOException {
			if(onDiskFile == null)
				return super.openInputStream();
			return Files.newInputStream(onDiskFile.toPath());
		}

		@Override
		public InputStream openInputStreamWithAutoDestroy() throws IOException {
			if(onDiskFile == null) {
				return super.openInputStreamWithAutoDestroy();
			}
			return new FileInputStream(onDiskFile) {
				@Override
				public void close() throws IOException {
					super.close();
					destroy();
				}
			};
		}

		@Override
		public void destroy() {
			super.destroy();

			if(onDiskFile != null) {
				try {
					if(!onDiskFile.delete())
						onDiskFile.deleteOnExit();
				} finally {
					onDiskFile = null;
				}
			}
		}
	}

	public static class Heap extends TemporaryBuffer {

		public Heap(int limit) {
			super(limit);
		}

		public Heap(int estimatedSize, int limit) {
			super(estimatedSize, limit);
		}

		@Override
		protected OutputStream overflow() throws IOException {
			throw new IOException(JGitText.get().inMemoryBufferLimitExceeded);
		}
	}

	static class Block {
		static final int SZ = 8 * 1024;

		final byte[] buffer;

		int count;

		Block() {
			buffer = new byte[SZ];
		}

		Block(int sz) {
			buffer = new byte[sz];
		}

		boolean isFull() {
			return count == buffer.length;
		}
	}

	private class BlockInputStream extends InputStream {
		private byte[] singleByteBuffer;
		private int blockIndex;
		private final Block block;
		private int blockPos;

		BlockInputStream() {
			block = blocks.get(blockIndex);
		}

		@Override
		public int read() throws IOException {
			if(singleByteBuffer == null)
				singleByteBuffer = new byte[1];
			int n = read(singleByteBuffer);
			return n == 1 ? singleByteBuffer[0] & 0xff : -1;
		}

		@Override
		public long skip(long cnt) throws IOException {
			long skipped = 0;
			while(0 < cnt) {
				int n = (int) Math.min(block.count - blockPos, cnt);
				if(0 < n) {
					blockPos += n;
					skipped += n;
					cnt -= n;
				} else
					break;
			}
			return skipped;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			if(len == 0)
				return 0;
			int copied = 0;
			while(0 < len) {
				int c = Math.min(block.count - blockPos, len);
				if(0 < c) {
					System.arraycopy(block.buffer, blockPos, b, off, c);
					blockPos += c;
					off += c;
					len -= c;
					copied += c;
				} else
					break;
			}
			return 0 < copied ? copied : -1;
		}
	}
}
