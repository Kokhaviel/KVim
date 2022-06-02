/*
 * Copyright (C) 2010, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.pack;

import org.eclipse.jgit.errors.LargeObjectException;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.storage.pack.PackConfig;
import org.eclipse.jgit.util.TemporaryBuffer;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.Deflater;

final class DeltaWindow {
	private static final boolean NEXT_RES = false;
	private static final boolean NEXT_SRC = true;

	private final PackConfig config;
	private final DeltaCache deltaCache;
	private final ObjectReader reader;
	private final ProgressMonitor monitor;
	private final long bytesPerUnit;
	private long bytesProcessed;
	private final long maxMemory;
	private final int maxDepth;
	private final ObjectToPack[] toSearch;
	private int cur;
	private int end;
	private long loaded;
	private DeltaWindowEntry res;
	private DeltaWindowEntry bestBase;
	private int deltaLen;
	private Object deltaBuf;

	private Deflater deflater;

	DeltaWindow(PackConfig pc, DeltaCache dc, ObjectReader or,
			ProgressMonitor pm, long bpu,
			ObjectToPack[] in, int beginIndex, int endIndex) {
		config = pc;
		deltaCache = dc;
		reader = or;
		monitor = pm;
		bytesPerUnit = bpu;
		toSearch = in;
		cur = beginIndex;
		end = endIndex;

		maxMemory = Math.max(0, config.getDeltaSearchMemoryLimit());
		maxDepth = config.getMaxDeltaDepth();
		res = DeltaWindowEntry.createWindow(config.getDeltaSearchWindowSize());
	}

	synchronized DeltaTask.Slice remaining() {
		int e = end;
		int halfRemaining = (e - cur) >>> 1;
		if (0 == halfRemaining)
			return null;

		int split = e - halfRemaining;
		int h = toSearch[split].getPathHash();

		for (int n = split + 1; n < e; n++) {
			if (h != toSearch[n].getPathHash())
				return new DeltaTask.Slice(n, e);
		}

		if (h != toSearch[cur].getPathHash()) {
			for (int p = split - 1; cur < p; p--) {
				if (h != toSearch[p].getPathHash())
					return new DeltaTask.Slice(p + 1, e);
			}
		}
		return null;
	}

	synchronized boolean tryStealWork(DeltaTask.Slice s) {
		if (s.beginIndex <= cur || end <= s.beginIndex)
			return false;
		end = s.beginIndex;
		return true;
	}

	void search() throws IOException {
		try {
			for (;;) {
				ObjectToPack next;
				synchronized (this) {
					if (end <= cur)
						break;
					next = toSearch[cur++];
				}
				if (maxMemory != 0) {
					clear(res);
					final long need = estimateSize(next);
					DeltaWindowEntry n = res.next;
					for (; maxMemory < loaded + need && n != res; n = n.next)
						clear(n);
				}
				res.set(next);
				clearWindowOnTypeSwitch();

				if (res.object.isEdge() || res.object.doNotAttemptDelta()) {
					keepInWindow();
				} else {
					if (bytesPerUnit <= (bytesProcessed += next.getWeight())) {
						int d = (int) (bytesProcessed / bytesPerUnit);
						monitor.update(d);
						bytesProcessed -= d * bytesPerUnit;
					}
					searchInWindow();
				}
			}
		} finally {
			if (deflater != null)
				deflater.end();
		}
	}

	private static long estimateSize(ObjectToPack ent) {
		return DeltaIndex.estimateIndexSize(ent.getWeight());
	}

	private static long estimateIndexSize(DeltaWindowEntry ent) {
		if (ent.buffer == null)
			return estimateSize(ent.object);

		int len = ent.buffer.length;
		return DeltaIndex.estimateIndexSize(len) - len;
	}

	private void clearWindowOnTypeSwitch() {
		DeltaWindowEntry p = res.prev;
		if (!p.empty() && res.type() != p.type()) {
			for (; p != res; p = p.prev) {
				clear(p);
			}
		}
	}

	private void clear(DeltaWindowEntry ent) {
		if (ent.index != null)
			loaded -= ent.index.getIndexSize();
		else if (ent.buffer != null)
			loaded -= ent.buffer.length;
		ent.set(null);
	}

	private void searchInWindow() throws IOException {
		for (DeltaWindowEntry src = res.prev; src != res; src = src.prev) {
			if (src.empty())
				break;
			if (delta(src))
				continue;
			bestBase = null;
			deltaBuf = null;
			return;
		}

		if (bestBase == null) {
			keepInWindow();
			return;
		}

		ObjectToPack srcObj = bestBase.object;
		ObjectToPack resObj = res.object;
		if (srcObj.isEdge()) {
			resObj.setDeltaBase(srcObj.copy());
		} else {
			resObj.setDeltaBase(srcObj);
		}

		int depth = srcObj.getDeltaDepth() + 1;
		resObj.setDeltaDepth(depth);
		resObj.clearReuseAsIs();
		cacheDelta(srcObj, resObj);

		if (depth < maxDepth) {
			res.makeNext(bestBase);
			res = bestBase.next;
		}

		bestBase = null;
		deltaBuf = null;
	}

	private boolean delta(DeltaWindowEntry src) throws IOException {
		if (res.size() < src.size() >>> 4)
			return NEXT_SRC;

		int msz = deltaSizeLimit(src);
		if (msz <= 8)
			return NEXT_SRC;

		if (res.size() - src.size() > msz)
			return NEXT_SRC;

		DeltaIndex srcIndex;
		try {
			srcIndex = index(src);
		} catch (LargeObjectException tooBig) {
			return NEXT_SRC;
		} catch (IOException notAvailable) {
			if (src.object.isEdge()) return NEXT_SRC;
			throw notAvailable;
		}

		byte[] resBuf;
		try {
			resBuf = buffer(res);
		} catch (LargeObjectException tooBig) {
			return NEXT_RES;
		}

		try {
			OutputStream delta = msz <= (8 << 10)
				? new ArrayStream(msz)
				: new TemporaryBuffer.Heap(msz);
			if (srcIndex.encode(delta, resBuf, msz))
				selectDeltaBase(src, delta);
		} catch (IOException ignored) {
		}
		return NEXT_SRC;
	}

	private void selectDeltaBase(DeltaWindowEntry src, OutputStream delta) {
		bestBase = src;

		if (delta instanceof ArrayStream) {
			ArrayStream a = (ArrayStream) delta;
			deltaBuf = a.buf;
			deltaLen = a.cnt;
		} else {
			TemporaryBuffer.Heap b = (TemporaryBuffer.Heap) delta;
			deltaBuf = b;
			deltaLen = (int) b.length();
		}
	}

	private int deltaSizeLimit(DeltaWindowEntry src) {
		if (bestBase == null) {
			int n = res.size() >>> 1;
			return n * (maxDepth - src.depth()) / maxDepth;
		}

		int d = bestBase.depth();
		int n = deltaLen;

		return n * (maxDepth - src.depth()) / (maxDepth - d);
	}

	private void cacheDelta(ObjectToPack srcObj, ObjectToPack resObj) {
		if (deltaCache.canCache(deltaLen, srcObj, resObj)) {
			try {
				byte[] zbuf = new byte[deflateBound(deltaLen)];
				ZipStream zs = new ZipStream(deflater(), zbuf);
				if (deltaBuf instanceof byte[])
					zs.write((byte[]) deltaBuf, 0, deltaLen);
				else
					((TemporaryBuffer.Heap) deltaBuf).writeTo(zs, null);
				deltaBuf = null;
				int len = zs.finish();

				resObj.setCachedDelta(deltaCache.cache(zbuf, len, deltaLen));
				resObj.setCachedSize(deltaLen);
			} catch (IOException | OutOfMemoryError err) {
				deltaCache.credit(deltaLen);
			}
		}
	}

	private static int deflateBound(int insz) {
		return insz + ((insz + 7) >> 3) + ((insz + 63) >> 6) + 11;
	}

	private void keepInWindow() {
		res = res.next;
	}

	private DeltaIndex index(DeltaWindowEntry ent) throws IOException, LargeObjectException {
		DeltaIndex idx = ent.index;
		if (idx == null) {
			checkLoadable(ent, estimateIndexSize(ent));

			try {
				idx = new DeltaIndex(buffer(ent));
			} catch (OutOfMemoryError noMemory) {
				LargeObjectException.OutOfMemory e;
				e = new LargeObjectException.OutOfMemory(noMemory);
				e.setObjectId(ent.object);
				throw e;
			}
			if (maxMemory != 0)
				loaded += idx.getIndexSize() - idx.getSourceSize();
			ent.index = idx;
		}
		return idx;
	}

	private byte[] buffer(DeltaWindowEntry ent) throws IOException, LargeObjectException {
		byte[] buf = ent.buffer;
		if (buf == null) {
			checkLoadable(ent, ent.size());

			buf = PackWriter.buffer(config, reader, ent.object);
			if (maxMemory != 0)
				loaded += buf.length;
			ent.buffer = buf;
		}
		return buf;
	}

	private void checkLoadable(DeltaWindowEntry ent, long need) {
		if (maxMemory == 0)
			return;

		DeltaWindowEntry n = res.next;
		for (; maxMemory < loaded + need; n = n.next) {
			clear(n);
			if (n == ent)
				throw new LargeObjectException.ExceedsLimit(
						maxMemory, loaded + need);
		}
	}

	private Deflater deflater() {
		if (deflater == null)
			deflater = new Deflater(config.getCompressionLevel());
		else
			deflater.reset();
		return deflater;
	}

	static final class ZipStream extends OutputStream {
		private final Deflater deflater;

		private final byte[] zbuf;

		private int outPtr;

		ZipStream(Deflater deflater, byte[] zbuf) {
			this.deflater = deflater;
			this.zbuf = zbuf;
		}

		int finish() throws IOException {
			deflater.finish();
			for (;;) {
				if (outPtr == zbuf.length)
					throw new EOFException();

				int n = deflater.deflate(zbuf, outPtr, zbuf.length - outPtr);
				if (n == 0) {
					if (deflater.finished())
						return outPtr;
					throw new IOException();
				}
				outPtr += n;
			}
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			deflater.setInput(b, off, len);
			for (;;) {
				if (outPtr == zbuf.length)
					throw new EOFException();

				int n = deflater.deflate(zbuf, outPtr, zbuf.length - outPtr);
				if (n == 0) {
					if (deflater.needsInput())
						break;
					throw new IOException();
				}
				outPtr += n;
			}
		}

		@Override
		public void write(int b) {
			throw new UnsupportedOperationException();
		}
	}

	static final class ArrayStream extends OutputStream {
		final byte[] buf;
		int cnt;

		ArrayStream(int max) {
			buf = new byte[max];
		}

		@Override
		public void write(int b) throws IOException {
			if (cnt == buf.length)
				throw new IOException();
			buf[cnt++] = (byte) b;
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			if (len > buf.length - cnt)
				throw new IOException();
			System.arraycopy(b, off, buf, cnt, len);
			cnt += len;
		}
	}
}
