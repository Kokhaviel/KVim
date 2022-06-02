/*
 * Copyright (C) 2009, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.file;

import static org.eclipse.jgit.internal.storage.pack.PackExt.BITMAP_INDEX;
import static org.eclipse.jgit.internal.storage.pack.PackExt.INDEX;
import static org.eclipse.jgit.internal.storage.pack.PackExt.PACK;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.PackInvalidException;
import org.eclipse.jgit.errors.PackMismatchException;
import org.eclipse.jgit.errors.SearchForReuseTimeout;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.internal.storage.pack.ObjectToPack;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.eclipse.jgit.internal.storage.pack.PackWriter;
import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class PackDirectory {
	private final static Logger LOG = LoggerFactory.getLogger(PackDirectory.class);
	private static final PackList NO_PACKS = new PackList(FileSnapshot.DIRTY, new Pack[0]);

	private final Config config;
	private final File directory;
	private final AtomicReference<PackList> packList;

	PackDirectory(Config config, File directory) {
		this.config = config;
		this.directory = directory;
		packList = new AtomicReference<>(NO_PACKS);
	}

	File getDirectory() {
		return directory;
	}

	void create() throws IOException {
		FileUtils.mkdir(directory);
	}

	void close() {
		PackList packs = packList.get();
		if(packs != NO_PACKS && packList.compareAndSet(packs, NO_PACKS)) {
			for(Pack p : packs.packs) {
				p.close();
			}
		}
	}

	Collection<Pack> getPacks() {
		PackList list = packList.get();
		if(list == NO_PACKS) {
			list = scanPacks(list);
		}
		Pack[] packs = list.packs;
		return Collections.unmodifiableCollection(Arrays.asList(packs));
	}

	@Override
	public String toString() {
		return "PackDirectory[" + getDirectory() + "]";
	}

	boolean has(AnyObjectId objectId) {
		return getPack(objectId) != null;
	}

	@Nullable
	Pack getPack(AnyObjectId objectId) {
		PackList pList;
		do {
			pList = packList.get();
			for(Pack p : pList.packs) {
				try {
					if(p.hasObject(objectId)) {
						return p;
					}
				} catch(IOException e) {
					LOG.warn(MessageFormat.format(JGitText.get().unableToReadPackfile, p.getPackFile().getAbsolutePath()), e);
					remove(p);
				}
			}
		} while(searchPacksAgain(pList));
		return null;
	}

	boolean resolve(Set<ObjectId> matches, AbbreviatedObjectId id, int matchLimit) {
		int oldSize = matches.size();
		PackList pList;
		do {
			pList = packList.get();
			for(Pack p : pList.packs) {
				try {
					p.resolve(matches, id, matchLimit);
					p.resetTransientErrorCount();
				} catch(IOException e) {
					handlePackError(e, p);
				}
				if(matches.size() > matchLimit) {
					return false;
				}
			}
		} while(matches.size() == oldSize && searchPacksAgain(pList));
		return true;
	}

	ObjectLoader open(WindowCursor curs, AnyObjectId objectId) {
		PackList pList;
		do {
			SEARCH:
			for(; ; ) {
				pList = packList.get();
				for(Pack p : pList.packs) {
					try {
						ObjectLoader ldr = p.get(curs, objectId);
						p.resetTransientErrorCount();
						if(ldr != null)
							return ldr;
					} catch(PackMismatchException e) {
						if(searchPacksAgain(pList)) {
							continue SEARCH;
						}
					} catch(IOException e) {
						handlePackError(e, p);
					}
				}
				break;
			}
		} while(searchPacksAgain(pList));
		return null;
	}

	long getSize(WindowCursor curs, AnyObjectId id) {
		PackList pList;
		do {
			SEARCH:
			for(; ; ) {
				pList = packList.get();
				for(Pack p : pList.packs) {
					try {
						long len = p.getObjectSize(curs, id);
						p.resetTransientErrorCount();
						if(0 <= len) {
							return len;
						}
					} catch(PackMismatchException e) {
						if(searchPacksAgain(pList)) {
							continue SEARCH;
						}
					} catch(IOException e) {
						handlePackError(e, p);
					}
				}
				break;
			}
		} while(searchPacksAgain(pList));
		return -1;
	}

	void selectRepresentation(PackWriter packer, ObjectToPack otp,
							  WindowCursor curs) {
		PackList pList = packList.get();
		SEARCH:
		for(; ; ) {
			for(Pack p : pList.packs) {
				try {
					LocalObjectRepresentation rep = p.representation(curs, otp);
					p.resetTransientErrorCount();
					if(rep != null) {
						packer.select(otp, rep);
						packer.checkSearchForReuseTimeout();
					}
				} catch(SearchForReuseTimeout e) {
					break SEARCH;
				} catch(PackMismatchException e) {
					pList = scanPacks(pList);
					continue SEARCH;
				} catch(IOException e) {
					handlePackError(e, p);
				}
			}
			break;
		}
	}

	private void handlePackError(IOException e, Pack p) {
		String warnTmpl = null;
		int transientErrorCount = 0;
		String errTmpl = JGitText.get().exceptionWhileReadingPack;
		if((e instanceof CorruptObjectException)
				|| (e instanceof PackInvalidException)) {
			warnTmpl = JGitText.get().corruptPack;
			LOG.warn(MessageFormat.format(warnTmpl,
					p.getPackFile().getAbsolutePath()), e);
			remove(p);
		} else if(e instanceof FileNotFoundException) {
			if(p.getPackFile().exists()) {
				errTmpl = JGitText.get().packInaccessible;
				transientErrorCount = p.incrementTransientErrorCount();
			} else {
				warnTmpl = JGitText.get().packWasDeleted;
				remove(p);
			}
		} else if(FileUtils.isStaleFileHandleInCausalChain(e)) {
			warnTmpl = JGitText.get().packHandleIsStale;
			remove(p);
		} else {
			transientErrorCount = p.incrementTransientErrorCount();
		}
		if(warnTmpl != null) {
			LOG.warn(MessageFormat.format(warnTmpl,
					p.getPackFile().getAbsolutePath()), e);
		} else {
			if(doLogExponentialBackoff(transientErrorCount)) {
				LOG.error(MessageFormat.format(errTmpl, p.getPackFile().getAbsolutePath(), transientErrorCount), e);
			}
		}
	}

	private boolean doLogExponentialBackoff(int n) {
		return (n & (n - 1)) == 0;
	}

	boolean searchPacksAgain(PackList old) {
		boolean trustFolderStat = config.getBoolean(
				ConfigConstants.CONFIG_CORE_SECTION,
				ConfigConstants.CONFIG_KEY_TRUSTFOLDERSTAT, true);

		return ((!trustFolderStat) || old.snapshot.isModified(directory))
				&& old != scanPacks(old);
	}

	void insert(Pack pack) {
		PackList o, n;
		do {
			o = packList.get();

			final Pack[] oldList = o.packs;
			final String name = pack.getPackFile().getName();
			for(Pack p : oldList) {
				if(name.equals(p.getPackFile().getName())) {
					return;
				}
			}

			final Pack[] newList = new Pack[1 + oldList.length];
			newList[0] = pack;
			System.arraycopy(oldList, 0, newList, 1, oldList.length);
			n = new PackList(o.snapshot, newList);
		} while(!packList.compareAndSet(o, n));
	}

	private void remove(Pack deadPack) {
		PackList o, n;
		do {
			o = packList.get();

			final Pack[] oldList = o.packs;
			final int j = indexOf(oldList, deadPack);
			if(j < 0) {
				break;
			}

			final Pack[] newList = new Pack[oldList.length - 1];
			System.arraycopy(oldList, 0, newList, 0, j);
			System.arraycopy(oldList, j + 1, newList, j, newList.length - j);
			n = new PackList(o.snapshot, newList);
		} while(!packList.compareAndSet(o, n));
		deadPack.close();
	}

	private static int indexOf(Pack[] list, Pack pack) {
		for(int i = 0; i < list.length; i++) {
			if(list[i] == pack) {
				return i;
			}
		}
		return -1;
	}

	private PackList scanPacks(PackList original) {
		synchronized(packList) {
			PackList o, n;
			do {
				o = packList.get();
				if(o != original) {
					return o;
				}
				n = scanPacksImpl(o);
				if(n == o) {
					return n;
				}
			} while(!packList.compareAndSet(o, n));
			return n;
		}
	}

	private PackList scanPacksImpl(PackList old) {
		final Map<String, Pack> forReuse = reuseMap(old);
		final FileSnapshot snapshot = FileSnapshot.save(directory);
		Map<String, Map<PackExt, PackFile>> packFilesByExtById = getPackFilesByExtById();
		List<Pack> list = new ArrayList<>(packFilesByExtById.size());
		boolean foundNew = false;
		for(Map<PackExt, PackFile> packFilesByExt : packFilesByExtById
				.values()) {
			PackFile packFile = packFilesByExt.get(PACK);
			if(packFile == null || !packFilesByExt.containsKey(INDEX)) {
				continue;
			}

			Pack oldPack = forReuse.get(packFile.getName());
			if(oldPack != null
					&& !oldPack.getFileSnapshot().isModified(packFile)) {
				forReuse.remove(packFile.getName());
				list.add(oldPack);
				continue;
			}

			list.add(new Pack(packFile, packFilesByExt.get(BITMAP_INDEX)));
			foundNew = true;
		}

		if(!foundNew && forReuse.isEmpty() && snapshot.equals(old.snapshot)) {
			old.snapshot.setClean(snapshot);
			return old;
		}

		for(Pack p : forReuse.values()) {
			p.close();
		}

		if(list.isEmpty()) {
			return new PackList(snapshot, NO_PACKS.packs);
		}

		final Pack[] r = list.toArray(new Pack[0]);
		Arrays.sort(r, Pack.SORT);
		return new PackList(snapshot, r);
	}

	private static Map<String, Pack> reuseMap(PackList old) {
		final Map<String, Pack> forReuse = new HashMap<>();
		for(Pack p : old.packs) {
			if(p.invalid()) {
				p.close();
				continue;
			}

			final Pack prior = forReuse.put(p.getPackFile().getName(), p);
			if(prior != null) {
				forReuse.put(prior.getPackFile().getName(), prior);
				p.close();
			}
		}
		return forReuse;
	}

	private Map<String, Map<PackExt, PackFile>> getPackFilesByExtById() {
		final String[] nameList = directory.list();
		if(nameList == null) {
			return Collections.emptyMap();
		}
		Map<String, Map<PackExt, PackFile>> packFilesByExtById = new HashMap<>(nameList.length / 2);
		for(String name : nameList) {
			try {
				PackFile pack = new PackFile(directory, name);
				if(pack.getPackExt() != null) {
					Map<PackExt, PackFile> packByExt = packFilesByExtById
							.get(pack.getId());
					if(packByExt == null) {
						packByExt = new EnumMap<>(PackExt.class);
						packFilesByExtById.put(pack.getId(), packByExt);
					}
					packByExt.put(pack.getPackExt(), pack);
				}
			} catch(IllegalArgumentException ignored) {
			}
		}
		return packFilesByExtById;
	}

	static final class PackList {
		final FileSnapshot snapshot;
		final Pack[] packs;

		PackList(FileSnapshot monitor, Pack[] packs) {
			this.snapshot = monitor;
			this.packs = packs;
		}
	}
}
