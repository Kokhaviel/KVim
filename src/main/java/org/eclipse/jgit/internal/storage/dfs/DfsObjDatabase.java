/*
 * Copyright (C) 2011, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.dfs;

import static java.util.stream.Collectors.joining;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.ObjectDatabase;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;

public abstract class DfsObjDatabase extends ObjectDatabase {
	private static final PackList NO_PACKS = new PackList(
			new DfsPackFile[0],
			new DfsReftable[0]) {
		@Override
		boolean dirty() {
			return true;
		}

		@Override
		void clearDirty() {
		}

	};

	public enum PackSource {
		INSERT,
		RECEIVE,
		COMPACT,
		GC,
		GC_REST,
		UNREACHABLE_GARBAGE;
		public static final Comparator<PackSource> DEFAULT_COMPARATOR =
				new ComparatorBuilder().add(INSERT, RECEIVE).add(COMPACT).add(GC).add(GC_REST).add(UNREACHABLE_GARBAGE).build();

		public static class ComparatorBuilder {
			private final Map<PackSource, Integer> ranks = new HashMap<>();
			private int counter;

			public ComparatorBuilder add(PackSource... sources) {
				for(PackSource s : sources) {
					ranks.put(s, counter);
				}
				counter++;
				return this;
			}

			public Comparator<PackSource> build() {
				return new PackSourceComparator(ranks);
			}
		}

		private static class PackSourceComparator implements Comparator<PackSource> {
			private final Map<PackSource, Integer> ranks;

			private PackSourceComparator(Map<PackSource, Integer> ranks) {
				if(!ranks.keySet().equals(
						new HashSet<>(Arrays.asList(PackSource.values())))) {
					throw new IllegalArgumentException();
				}
				this.ranks = new HashMap<>(ranks);
			}

			@Override
			public int compare(PackSource a, PackSource b) {
				return ranks.get(a).compareTo(ranks.get(b));
			}

			@Override
			public String toString() {
				return Arrays.stream(PackSource.values())
						.map(s -> s + "=" + ranks.get(s))
						.collect(joining(", ",
								getClass().getSimpleName() + "{", "}"));
			}
		}
	}

	private final AtomicReference<PackList> packList;
	private final DfsRepository repository;
	private final DfsReaderOptions readerOptions;
	private final Comparator<DfsPackDescription> packComparator;

	protected DfsObjDatabase(DfsRepository repository, DfsReaderOptions options) {
		this.repository = repository;
		this.packList = new AtomicReference<>(NO_PACKS);
		this.readerOptions = options;
		this.packComparator = DfsPackDescription.objectLookupComparator();
	}

	public DfsReaderOptions getReaderOptions() {
		return readerOptions;
	}

	@Override
	public DfsReader newReader() {
		return new DfsReader(this);
	}

	@Override
	public ObjectInserter newInserter() {
		return new DfsInserter(this);
	}

	public DfsPackFile[] getPacks() throws IOException {
		return getPackList().packs;
	}

	public DfsReftable[] getReftables() throws IOException {
		return getPackList().reftables;
	}

	public PackList getPackList() throws IOException {
		return scanPacks(NO_PACKS);
	}

	protected DfsRepository getRepository() {
		return repository;
	}

	public boolean has(AnyObjectId objectId, boolean avoidUnreachableObjects)
			throws IOException {
		try(ObjectReader or = newReader()) {
			or.setAvoidUnreachableObjects(avoidUnreachableObjects);
			return or.has(objectId);
		}
	}

	protected abstract DfsPackDescription newPack(PackSource source)
			throws IOException;

	protected void commitPack(Collection<DfsPackDescription> desc,
							  Collection<DfsPackDescription> replaces) throws IOException {
		commitPackImpl(desc, replaces);
		getRepository().fireEvent(new DfsPacksChangedEvent());
	}

	protected abstract void commitPackImpl(Collection<DfsPackDescription> desc,
										   Collection<DfsPackDescription> replaces) throws IOException;

	protected abstract void rollbackPack(Collection<DfsPackDescription> desc);

	protected abstract List<DfsPackDescription> listPacks() throws IOException;

	protected abstract ReadableChannel openFile(
			DfsPackDescription desc, PackExt ext) throws IOException;

	protected abstract DfsOutputStream writeFile(DfsPackDescription desc, PackExt ext) throws IOException;

	void addPack(DfsPackFile newPack) throws IOException {
		PackList o, n;
		do {
			o = packList.get();
			if(o == NO_PACKS) {
				o = scanPacks(o);
				for(DfsPackFile p : o.packs) {
					if(p.key.equals(newPack.key)) {
						return;
					}
				}
			}

			DfsPackFile[] packs = new DfsPackFile[1 + o.packs.length];
			packs[0] = newPack;
			System.arraycopy(o.packs, 0, packs, 1, o.packs.length);
			n = new PackListImpl(packs, o.reftables);
		} while(!packList.compareAndSet(o, n));
	}

	void addReftable(DfsPackDescription add, Set<DfsPackDescription> remove)
			throws IOException {
		PackList o, n;
		do {
			o = packList.get();
			if(o == NO_PACKS) {
				o = scanPacks(o);
				for(DfsReftable t : o.reftables) {
					if(t.getPackDescription().equals(add)) {
						return;
					}
				}
			}

			List<DfsReftable> tables = new ArrayList<>(1 + o.reftables.length);
			for(DfsReftable t : o.reftables) {
				if(!remove.contains(t.getPackDescription())) {
					tables.add(t);
				}
			}
			tables.add(new DfsReftable(add));
			n = new PackListImpl(o.packs, tables.toArray(new DfsReftable[0]));
		} while(!packList.compareAndSet(o, n));
	}

	PackList scanPacks(PackList original) throws IOException {
		PackList o, n;
		synchronized(packList) {
			do {
				o = packList.get();
				if(o != original) {
					return o;
				}
				n = scanPacksImpl(o);
				if(n == o)
					return n;
			} while(!packList.compareAndSet(o, n));
		}
		getRepository().fireEvent(new DfsPacksChangedEvent());
		return n;
	}

	private PackList scanPacksImpl(PackList old) throws IOException {
		DfsBlockCache cache = DfsBlockCache.getInstance();
		Map<DfsPackDescription, DfsPackFile> packs = packMap(old);
		Map<DfsPackDescription, DfsReftable> reftables = reftableMap(old);

		List<DfsPackDescription> scanned = listPacks();
		scanned.sort(packComparator);

		List<DfsPackFile> newPacks = new ArrayList<>(scanned.size());
		List<DfsReftable> newReftables = new ArrayList<>(scanned.size());
		boolean foundNew = false;
		for(DfsPackDescription dsc : scanned) {
			DfsPackFile oldPack = packs.remove(dsc);
			if(oldPack != null) {
				newPacks.add(oldPack);
			} else if(dsc.hasFileExt(PackExt.PACK)) {
				newPacks.add(new DfsPackFile(cache, dsc));
				foundNew = true;
			}

			DfsReftable oldReftable = reftables.remove(dsc);
			if(oldReftable != null) {
				newReftables.add(oldReftable);
			} else if(dsc.hasFileExt(PackExt.REFTABLE)) {
				newReftables.add(new DfsReftable(cache, dsc));
				foundNew = true;
			}
		}

		if(newPacks.isEmpty() && newReftables.isEmpty())
			return new PackListImpl(NO_PACKS.packs, NO_PACKS.reftables);
		if(!foundNew) {
			old.clearDirty();
			return old;
		}
		newReftables.sort(reftableComparator());
		return new PackListImpl(
				newPacks.toArray(new DfsPackFile[0]),
				newReftables.toArray(new DfsReftable[0]));
	}

	private static Map<DfsPackDescription, DfsPackFile> packMap(PackList old) {
		Map<DfsPackDescription, DfsPackFile> forReuse = new HashMap<>();
		for(DfsPackFile p : old.packs) {
			if(!p.invalid()) {
				forReuse.put(p.desc, p);
			}
		}
		return forReuse;
	}

	private static Map<DfsPackDescription, DfsReftable> reftableMap(PackList old) {
		Map<DfsPackDescription, DfsReftable> forReuse = new HashMap<>();
		for(DfsReftable p : old.reftables) {
			if(!p.invalid()) {
				forReuse.put(p.desc, p);
			}
		}
		return forReuse;
	}

	protected Comparator<DfsReftable> reftableComparator() {
		return Comparator.comparing(
				DfsReftable::getPackDescription,
				DfsPackDescription.reftableComparator());
	}

	@Override
	public void close() {
		packList.set(NO_PACKS);
	}

	public abstract static class PackList {
		public final DfsPackFile[] packs;
		public final DfsReftable[] reftables;

		PackList(DfsPackFile[] packs, DfsReftable[] reftables) {
			this.packs = packs;
			this.reftables = reftables;
		}

		abstract boolean dirty();

		abstract void clearDirty();
	}

	private static final class PackListImpl extends PackList {
		private volatile boolean dirty;

		PackListImpl(DfsPackFile[] packs, DfsReftable[] reftables) {
			super(packs, reftables);
		}

		@Override
		boolean dirty() {
			return dirty;
		}

		@Override
		void clearDirty() {
			dirty = false;
		}
	}
}
