/*
 * Copyright (C) 2019 Google LLC and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.file;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.errors.LockFailedException;
import org.eclipse.jgit.internal.storage.io.BlockSource;
import org.eclipse.jgit.internal.storage.reftable.MergedReftable;
import org.eclipse.jgit.internal.storage.reftable.ReftableCompactor;
import org.eclipse.jgit.internal.storage.reftable.ReftableConfig;
import org.eclipse.jgit.internal.storage.reftable.ReftableReader;
import org.eclipse.jgit.internal.storage.reftable.ReftableWriter;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.util.FileUtils;
import org.eclipse.jgit.util.SystemReader;

public class FileReftableStack implements AutoCloseable {
	private static class StackEntry {

		String name;
		ReftableReader reftableReader;
	}

	private MergedReftable mergedReftable;
	private List<StackEntry> stack;
	private long lastNextUpdateIndex;
	private final File stackPath;
	private final File reftableDir;
	private final Runnable onChange;
	private final SecureRandom random = new SecureRandom();
	private final Supplier<Config> configSupplier;

	static class CompactionStats {

		long tables;
		long bytes;
		int attempted;
		int failed;
		long refCount;
		long logCount;

		CompactionStats() {
			tables = 0;
			bytes = 0;
			attempted = 0;
			failed = 0;
			logCount = 0;
			refCount = 0;
		}
	}

	private final CompactionStats stats;

	public FileReftableStack(File stackPath, File reftableDir,
							 @Nullable Runnable onChange, Supplier<Config> configSupplier)
			throws IOException {
		this.stackPath = stackPath;
		this.reftableDir = reftableDir;
		this.stack = new ArrayList<>();
		this.configSupplier = configSupplier;
		this.onChange = onChange;

		lastNextUpdateIndex = 0;
		reload();

		stats = new CompactionStats();
	}

	private void reloadOnce(List<String> names) throws IOException {
		Map<String, ReftableReader> current = stack.stream().collect(Collectors.toMap(e -> e.name, e -> e.reftableReader));
		List<ReftableReader> newTables = new ArrayList<>();
		List<StackEntry> newStack = new ArrayList<>(stack.size() + 1);
		try {
			for(String name : names) {
				StackEntry entry = new StackEntry();
				entry.name = name;

				ReftableReader t;
				if(current.containsKey(name)) {
					t = current.remove(name);
				} else {
					File subtable = new File(reftableDir, name);
					FileInputStream is;

					is = new FileInputStream(subtable);

					t = new ReftableReader(BlockSource.from(is));
					newTables.add(t);
				}

				entry.reftableReader = t;
				newStack.add(entry);
			}

			stack = newStack;
			newTables.clear();

			current.values().forEach(r -> {
				try {
					r.close();
				} catch(IOException e) {
					throw new AssertionError(e);
				}
			});
		} finally {
			newTables.forEach(t -> {
				try {
					t.close();
				} catch(IOException ioe) {
					throw new AssertionError(ioe);
				}
			});
		}
	}

	void reload() throws IOException {
		long deadline = System.currentTimeMillis() + 2500;
		long min = 1;
		long max = 1000;
		long delay = 0;
		boolean success = false;

		int tries = 0;
		while(tries < 3 || System.currentTimeMillis() < deadline) {
			List<String> names = readTableNames();
			tries++;
			try {
				reloadOnce(names);
				success = true;
				break;
			} catch(FileNotFoundException e) {
				List<String> changed = readTableNames();
				if(changed.equals(names)) {
					throw e;
				}
			}

			delay = FileUtils.delay(delay, min, max);
			try {
				Thread.sleep(delay);
			} catch(InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new RuntimeException(e);
			}
		}

		if(!success) {
			throw new LockFailedException(stackPath);
		}

		mergedReftable = new MergedReftable(stack.stream()
				.map(x -> x.reftableReader).collect(Collectors.toList()));
		long curr = nextUpdateIndex();
		if(lastNextUpdateIndex > 0 && lastNextUpdateIndex != curr
				&& onChange != null) {
			onChange.run();
		}
		lastNextUpdateIndex = curr;
	}

	public MergedReftable getMergedReftable() {
		return mergedReftable;
	}

	public interface Writer {

		void call(ReftableWriter w) throws IOException;
	}

	private List<String> readTableNames() throws IOException {
		List<String> names = new ArrayList<>(stack.size() + 1);

		try(BufferedReader br = new BufferedReader(
				new InputStreamReader(new FileInputStream(stackPath), UTF_8))) {
			String line;
			while((line = br.readLine()) != null) {
				if(!line.isEmpty()) {
					names.add(line);
				}
			}
		} catch(FileNotFoundException ignored) {
		}
		return names;
	}

	boolean isUpToDate() throws IOException {
		try {
			List<String> names = readTableNames();
			if(names.size() != stack.size()) {
				return false;
			}
			for(int i = 0; i < names.size(); i++) {
				if(!names.get(i).equals(stack.get(i).name)) {
					return false;
				}
			}
		} catch(FileNotFoundException e) {
			return stack.isEmpty();
		}
		return true;
	}

	@Override
	public void close() {
		for(StackEntry entry : stack) {
			try {
				entry.reftableReader.close();
			} catch(Exception e) {
				throw new AssertionError(e);
			}
		}
	}

	private long nextUpdateIndex() throws IOException {
		return stack.size() > 0
				? stack.get(stack.size() - 1).reftableReader.maxUpdateIndex()
				+ 1
				: 1;
	}

	private String filename(long low, long high) {
		return String.format("%012x-%012x-%08x", low, high, random.nextInt());
	}

	@SuppressWarnings("nls")
	public boolean addReftable(Writer w) throws IOException {
		LockFile lock = new LockFile(stackPath);
		try {
			if(!lock.lockForAppend()) {
				return false;
			}
			if(!isUpToDate()) {
				return false;
			}

			String fn = filename(nextUpdateIndex(), nextUpdateIndex());

			File tmpTable = File.createTempFile(fn + "_", ".ref",
					stackPath.getParentFile());

			ReftableWriter.Stats s;
			try(FileOutputStream fos = new FileOutputStream(tmpTable)) {
				ReftableWriter rw = new ReftableWriter(reftableConfig(), fos);
				w.call(rw);
				rw.finish();
				s = rw.getStats();
			}

			if(s.minUpdateIndex() < nextUpdateIndex()) {
				return false;
			}

			fn += s.refCount() > 0 ? ".ref" : ".log";
			File dest = new File(reftableDir, fn);

			FileUtils.rename(tmpTable, dest, StandardCopyOption.ATOMIC_MOVE);
			lock.write((fn + "\n").getBytes(UTF_8));
			if(!lock.commit()) {
				FileUtils.delete(dest);
				return false;
			}

			reload();

			autoCompact();
		} finally {
			lock.unlock();
		}
		return true;
	}

	private ReftableConfig reftableConfig() {
		return new ReftableConfig(configSupplier.get());
	}

	private File compactLocked(int first, int last) throws IOException {
		String fn = filename(first, last);

		File tmpTable = File.createTempFile(fn + "_", ".ref",
				stackPath.getParentFile());
		try(FileOutputStream fos = new FileOutputStream(tmpTable)) {
			ReftableCompactor c = new ReftableCompactor(fos)
					.setConfig(reftableConfig())
					.setIncludeDeletes(first > 0);

			List<ReftableReader> compactMe = new ArrayList<>();
			long totalBytes = 0;
			for(int i = first; i <= last; i++) {
				compactMe.add(stack.get(i).reftableReader);
				totalBytes += stack.get(i).reftableReader.size();
			}
			c.addAll(compactMe);

			c.compact();

			stats.bytes += totalBytes;
			stats.tables += first - last + 1;
			stats.attempted++;
			stats.refCount += c.getStats().refCount();
			stats.logCount += c.getStats().logCount();
		}

		return tmpTable;
	}

	boolean compactRange(int first, int last) throws IOException {
		if(first >= last) {
			return true;
		}
		LockFile lock = new LockFile(stackPath);

		File tmpTable = null;
		List<LockFile> subtableLocks = new ArrayList<>();

		try {
			if(!lock.lock()) {
				return false;
			}
			if(!isUpToDate()) {
				return false;
			}

			List<File> deleteOnSuccess = new ArrayList<>();
			for(int i = first; i <= last; i++) {
				File f = new File(reftableDir, stack.get(i).name);
				LockFile lf = new LockFile(f);
				if(!lf.lock()) {
					return false;
				}
				subtableLocks.add(lf);
				deleteOnSuccess.add(f);
			}

			lock.unlock();
			lock = null;

			tmpTable = compactLocked(first, last);

			lock = new LockFile(stackPath);
			if(!lock.lock()) {
				return false;
			}
			if(!isUpToDate()) {
				return false;
			}

			String fn = filename(
					stack.get(first).reftableReader.minUpdateIndex(),
					stack.get(last).reftableReader.maxUpdateIndex());

			fn += ".ref";
			File dest = new File(reftableDir, fn);

			FileUtils.rename(tmpTable, dest, StandardCopyOption.ATOMIC_MOVE);
			tmpTable = null;

			StringBuilder sb = new StringBuilder();

			for(int i = 0; i < first; i++) {
				sb.append(stack.get(i).name).append("\n");
			}
			sb.append(fn).append("\n");
			for(int i = last + 1; i < stack.size(); i++) {
				sb.append(stack.get(i).name).append("\n");
			}

			lock.write(sb.toString().getBytes(UTF_8));
			if(!lock.commit()) {
				dest.delete();
				return false;
			}

			reload();
			for(File f : deleteOnSuccess) {
				try {
					Files.delete(f.toPath());
				} catch(IOException e) {
					if(!SystemReader.getInstance().isWindows()) {
						throw e;
					}
				}
			}

			return true;
		} finally {
			if(tmpTable != null) {
				tmpTable.delete();
			}
			for(LockFile lf : subtableLocks) {
				lf.unlock();
			}
			if(lock != null) {
				lock.unlock();
			}
		}
	}

	static int log(long sz) {
		long base = 2;
		if(sz <= 0) {
			throw new IllegalArgumentException("log2 negative");
		}
		int l = 0;
		while(sz > 0) {
			l++;
			sz /= base;
		}

		return l - 1;
	}

	static class Segment {
		int log;
		long bytes;
		int start;
		int end;

		int size() {
			return end - start;
		}

		Segment(int start, int end, int log, long bytes) {
			this.log = log;
			this.start = start;
			this.end = end;
			this.bytes = bytes;
		}

		Segment() {
			this(0, 0, 0, 0);
		}

		@Override
		public int hashCode() {
			return 0;
		}

		@Override
		public boolean equals(Object other) {
			if(other == null) {
				return false;
			}
			Segment o = (Segment) other;
			return o.bytes == bytes && o.log == log && o.start == start && o.end == end;
		}

		@Override
		public String toString() {
			return String.format("{ [%d,%d) l=%d sz=%d }", start, end, log,
					bytes);
		}
	}

	static List<Segment> segmentSizes(long[] sizes) {
		List<Segment> segments = new ArrayList<>();
		Segment cur = new Segment();
		for(int i = 0; i < sizes.length; i++) {
			int l = log(sizes[i]);
			if(l != cur.log && cur.bytes > 0) {
				segments.add(cur);
				cur = new Segment();
				cur.start = i;
			}

			cur.log = l;
			cur.end = i + 1;
			cur.bytes += sizes[i];
		}
		segments.add(cur);
		return segments;
	}

	private static Optional<Segment> autoCompactCandidate(long[] sizes) {
		if(sizes.length == 0) return Optional.empty();

		List<Segment> segments = segmentSizes(sizes);
		segments = segments.stream().filter(s -> s.size() > 1)
				.collect(Collectors.toList());
		if(segments.isEmpty()) return Optional.empty();

		Optional<Segment> optMinSeg = segments.stream().min(Comparator.comparing(s -> s.log));
		Segment smallCollected = optMinSeg.get();
		while(smallCollected.start > 0) {
			int prev = smallCollected.start - 1;
			long prevSize = sizes[prev];
			if(log(smallCollected.bytes) < log(prevSize)) break;
			smallCollected.start = prev;
			smallCollected.bytes += prevSize;
		}

		return Optional.of(smallCollected);
	}

	private void autoCompact() throws IOException {
		Optional<Segment> cand = autoCompactCandidate(tableSizes());
		if(cand.isPresent()) {
			if(!compactRange(cand.get().start, cand.get().end - 1)) {
				stats.failed++;
			}
		}
	}

	private long[] tableSizes() throws IOException {
		long[] sizes = new long[stack.size()];
		for(int i = 0; i < stack.size(); i++) {
			long OVERHEAD = 91;
			sizes[i] = stack.get(i).reftableReader.size() - OVERHEAD;
		}
		return sizes;
	}

	void compactFully() throws IOException {
		if(!compactRange(0, stack.size() - 1)) {
			stats.failed++;
		}
	}
}
