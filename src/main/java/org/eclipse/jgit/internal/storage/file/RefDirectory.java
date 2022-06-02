/*
 * Copyright (C) 2007, Dave Watson <dwatson@mimvista.com>
 * Copyright (C) 2009-2010, Google Inc.
 * Copyright (C) 2007, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2006, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.file;

import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.errors.InvalidObjectIdException;
import org.eclipse.jgit.errors.LockFailedException;
import org.eclipse.jgit.errors.ObjectWritingException;
import org.eclipse.jgit.events.RefsChangedEvent;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.jgit.lib.Constants.*;
import static org.eclipse.jgit.lib.Ref.Storage.*;

public class RefDirectory extends RefDatabase {
	private static final Logger LOG = LoggerFactory.getLogger(RefDirectory.class);

	public static final String SYMREF = "ref: ";
	public static final String PACKED_REFS_HEADER = "# pack-refs with:";
	public static final String PACKED_REFS_PEELED = " peeled";

	private static final String[] additionalRefsNames = new String[] {
			Constants.MERGE_HEAD, Constants.FETCH_HEAD, Constants.ORIG_HEAD, Constants.CHERRY_PICK_HEAD};

	private static final List<Integer> RETRY_SLEEP_MS = Collections.unmodifiableList(Arrays.asList(0, 100, 200, 400, 800, 1600));

	private final FileRepository parent;
	private final File gitDir;

	final File refsDir;
	final File packedRefsFile;
	final File logsDir;
	final File logsRefsDir;

	private final AtomicReference<RefList<LooseRef>> looseRefs = new AtomicReference<>();

	final AtomicReference<PackedRefList> packedRefs = new AtomicReference<>();
	final ReentrantLock inProcessPackedRefsLock = new ReentrantLock(true);

	private final AtomicInteger modCnt = new AtomicInteger();
	private final AtomicInteger lastNotifiedModCnt = new AtomicInteger();
	private final List<Integer> retrySleepMs = RETRY_SLEEP_MS;

	RefDirectory(FileRepository db) {
		final FS fs = db.getFS();
		parent = db;
		gitDir = db.getDirectory();
		refsDir = fs.resolve(gitDir, R_REFS);
		logsDir = fs.resolve(gitDir, LOGS);
		logsRefsDir = fs.resolve(gitDir, LOGS + '/' + R_REFS);
		packedRefsFile = fs.resolve(gitDir, PACKED_REFS);

		looseRefs.set(RefList.emptyList());
		packedRefs.set(NO_PACKED_REFS);
	}

	Repository getRepository() {
		return parent;
	}

	ReflogWriter newLogWriter(boolean force) {
		return new ReflogWriter(this, force);
	}

	public File logFor(String name) {
		if(name.startsWith(R_REFS)) {
			name = name.substring(R_REFS.length());
			return new File(logsRefsDir, name);
		}
		return new File(logsDir, name);
	}

	@Override
	public void create() throws IOException {
		FileUtils.mkdir(refsDir);
		FileUtils.mkdir(new File(refsDir, R_HEADS.substring(R_REFS.length())));
		FileUtils.mkdir(new File(refsDir, R_TAGS.substring(R_REFS.length())));
		newLogWriter(false).create();
	}

	@Override
	public void close() {
		clearReferences();
	}

	private void clearReferences() {
		looseRefs.set(RefList.emptyList());
		packedRefs.set(NO_PACKED_REFS);
	}

	@Override
	public void refresh() {
		super.refresh();
		clearReferences();
	}

	@Override
	public boolean isNameConflicting(String name) throws IOException {
		int lastSlash = name.lastIndexOf('/');
		while(0 < lastSlash) {
			String needle = name.substring(0, lastSlash);
			if(exactRef(needle) != null) {
				return true;
			}
			lastSlash = name.lastIndexOf('/', lastSlash - 1);
		}

		return !getRefsByPrefix(name + '/').isEmpty();
	}

	@Nullable
	private Ref readAndResolve(String name, RefList<Ref> packed) throws IOException {
		try {
			Ref ref = readRef(name, packed);
			if(ref != null) {
				ref = resolve(ref, 0, null, null, packed);
			}
			return ref;
		} catch(IOException e) {
			if(name.contains("/")
					|| !(e.getCause() instanceof InvalidObjectIdException)) {
				throw e;
			}

			return null;
		}
	}

	@Override
	public Ref exactRef(String name) throws IOException {
		try {
			return readAndResolve(name, getPackedRefs());
		} finally {
			fireRefsChanged();
		}
	}

	@Override
	@NonNull
	public Map<String, Ref> exactRef(String... refs) throws IOException {
		try {
			RefList<Ref> packed = getPackedRefs();
			Map<String, Ref> result = new HashMap<>(refs.length);
			for(String name : refs) {
				Ref ref = readAndResolve(name, packed);
				if(ref != null) {
					result.put(name, ref);
				}
			}
			return result;
		} finally {
			fireRefsChanged();
		}
	}

	@Override
	@Nullable
	public Ref firstExactRef(String... refs) throws IOException {
		try {
			RefList<Ref> packed = getPackedRefs();
			for(String name : refs) {
				Ref ref = readAndResolve(name, packed);
				if(ref != null) {
					return ref;
				}
			}
			return null;
		} finally {
			fireRefsChanged();
		}
	}

	@Override
	public Map<String, Ref> getRefs(String prefix) throws IOException {
		final RefList<LooseRef> oldLoose = looseRefs.get();
		LooseScanner scan = new LooseScanner(oldLoose);
		scan.scan(prefix);
		final RefList<Ref> packed = getPackedRefs();

		RefList<LooseRef> loose;
		if(scan.newLoose != null) {
			scan.newLoose.sort();
			loose = scan.newLoose.toRefList();
			if(looseRefs.compareAndSet(oldLoose, loose))
				modCnt.incrementAndGet();
		} else
			loose = oldLoose;
		fireRefsChanged();

		RefList.Builder<Ref> symbolic = scan.symbolic;
		for(int idx = 0; idx < symbolic.size(); ) {
			final Ref symbolicRef = symbolic.get(idx);
			final Ref resolvedRef = resolve(symbolicRef, 0, prefix, loose, packed);
			if(resolvedRef != null && resolvedRef.getObjectId() != null) {
				symbolic.set(idx, resolvedRef);
				idx++;
			} else {
				symbolic.remove(idx);
				final int toRemove = loose.find(symbolicRef.getName());
				if(0 <= toRemove)
					loose = loose.remove(toRemove);
			}
		}
		symbolic.sort();

		return new RefMap(prefix, packed, upcast(loose), symbolic.toRefList());
	}

	@Override
	public List<Ref> getAdditionalRefs() throws IOException {
		List<Ref> ret = new LinkedList<>();
		for(String name : additionalRefsNames) {
			Ref r = exactRef(name);
			if(r != null)
				ret.add(r);
		}
		return ret;
	}

	@SuppressWarnings("unchecked")
	private RefList<Ref> upcast(RefList<? extends Ref> loose) {
		return (RefList<Ref>) loose;
	}

	private class LooseScanner {
		private final RefList<LooseRef> curLoose;
		private int curIdx;

		final RefList.Builder<Ref> symbolic = new RefList.Builder<>(4);

		RefList.Builder<LooseRef> newLoose;

		LooseScanner(RefList<LooseRef> curLoose) {
			this.curLoose = curLoose;
		}

		void scan(String prefix) {
			if(ALL.equals(prefix)) {
				scanOne(HEAD);
				scanTree(R_REFS, refsDir);

				if(newLoose == null && curIdx < curLoose.size())
					newLoose = curLoose.copy(curIdx);

			} else if(prefix.startsWith(R_REFS) && prefix.endsWith("/")) {
				curIdx = -(curLoose.find(prefix) + 1);
				File dir = new File(refsDir, prefix.substring(R_REFS.length()));
				scanTree(prefix, dir);

				while(curIdx < curLoose.size()) {
					if(!curLoose.get(curIdx).getName().startsWith(prefix))
						break;
					if(newLoose == null)
						newLoose = curLoose.copy(curIdx);
					curIdx++;
				}

				if(newLoose != null) {
					while(curIdx < curLoose.size())
						newLoose.add(curLoose.get(curIdx++));
				}
			}
		}

		private boolean scanTree(String prefix, File dir) {
			final String[] entries = dir.list(LockFile.FILTER);
			if(entries == null) return false;
			if(0 < entries.length) {
				for(int i = 0; i < entries.length; ++i) {
					String e = entries[i];
					File f = new File(dir, e);
					if(f.isDirectory())
						entries[i] += '/';
				}
				Arrays.sort(entries);
				for(String name : entries) {
					if(name.charAt(name.length() - 1) == '/')
						scanTree(prefix + name, new File(dir, name));
					else
						scanOne(prefix + name);
				}
			}
			return true;
		}

		private void scanOne(String name) {
			LooseRef cur;

			if(curIdx < curLoose.size()) {
				do {
					cur = curLoose.get(curIdx);
					int cmp = RefComparator.compareTo(cur, name);
					if(cmp < 0) {
						if(newLoose == null)
							newLoose = curLoose.copy(curIdx);
						curIdx++;
						cur = null;
						continue;
					}

					if(cmp > 0)
						cur = null;
					break;
				} while(curIdx < curLoose.size());
			} else
				cur = null;

			LooseRef n;
			try {
				n = scanRef(cur, name);
			} catch(IOException notValid) {
				n = null;
			}

			if(n != null) {
				if(cur != n && newLoose == null) newLoose = curLoose.copy(curIdx);
				if(newLoose != null) newLoose.add(n);
				if(n.isSymbolic()) symbolic.add(n);
			} else if(cur != null) {
				if(newLoose == null) newLoose = curLoose.copy(curIdx);
			}
			if(cur != null) curIdx++;
		}
	}

	@Override
	public Ref peel(Ref ref) throws IOException {
		final Ref leaf = ref.getLeaf();
		if(leaf.isPeeled() || leaf.getObjectId() == null)
			return ref;

		ObjectIdRef newLeaf = doPeel(leaf);

		if(leaf.getStorage().isLoose()) {
			RefList<LooseRef> curList = looseRefs.get();
			int idx = curList.find(leaf.getName());
			if(0 <= idx && curList.get(idx) == leaf) {
				LooseRef asPeeled = ((LooseRef) leaf).peel(newLeaf);
				RefList<LooseRef> newList = curList.set(idx, asPeeled);
				looseRefs.compareAndSet(curList, newList);
			}
		}

		return recreate(ref, newLeaf);
	}

	private ObjectIdRef doPeel(Ref leaf) throws IOException {
		try(RevWalk rw = new RevWalk(getRepository())) {
			RevObject obj = rw.parseAny(leaf.getObjectId());
			if(obj instanceof RevTag) {
				return new ObjectIdRef.PeeledTag(leaf.getStorage(), leaf
						.getName(), leaf.getObjectId(), rw.peel(obj).copy());
			}
			return new ObjectIdRef.PeeledNonTag(leaf.getStorage(),
					leaf.getName(), leaf.getObjectId());
		}
	}

	private static Ref recreate(Ref old, ObjectIdRef leaf) {
		if(old.isSymbolic()) {
			Ref dst = recreate(old.getTarget(), leaf);
			return new SymbolicRef(old.getName(), dst);
		}
		return leaf;
	}

	void storedSymbolicRef(RefDirectoryUpdate u, FileSnapshot snapshot,
						   String target) {
		putLooseRef(newSymbolicRef(snapshot, u.getRef().getName(), target));
		fireRefsChanged();
	}

	@Override
	public RefDirectoryUpdate newUpdate(String name, boolean detach)
			throws IOException {
		boolean detachingSymbolicRef = false;
		final RefList<Ref> packed = getPackedRefs();
		Ref ref = readRef(name, packed);
		if(ref != null)
			ref = resolve(ref, 0, null, null, packed);
		if(ref == null)
			ref = new ObjectIdRef.Unpeeled(NEW, name, null);
		else {
			detachingSymbolicRef = detach && ref.isSymbolic();
		}
		RefDirectoryUpdate refDirUpdate = new RefDirectoryUpdate(this, ref);
		if(detachingSymbolicRef)
			refDirUpdate.setDetachingSymbolicRef();
		return refDirUpdate;
	}

	@Override
	public PackedBatchRefUpdate newBatchUpdate() {
		return new PackedBatchRefUpdate(this);
	}

	@Override
	public boolean performsAtomicTransactions() {
		return true;
	}

	void stored(RefDirectoryUpdate update, FileSnapshot snapshot) {
		final ObjectId target = update.getNewObjectId().copy();
		final Ref leaf = update.getRef().getLeaf();
		putLooseRef(new LooseUnpeeled(snapshot, leaf.getName(), target));
	}

	private void putLooseRef(LooseRef ref) {
		RefList<LooseRef> cList, nList;
		do {
			cList = looseRefs.get();
			nList = cList.put(ref);
		} while(!looseRefs.compareAndSet(cList, nList));
		modCnt.incrementAndGet();
		fireRefsChanged();
	}

	void delete(RefDirectoryUpdate update) throws IOException {
		Ref dst = update.getRef();
		if(!update.isDetachingSymbolicRef()) {
			dst = dst.getLeaf();
		}
		String name = dst.getName();

		final PackedRefList packed = getPackedRefs();
		if(packed.contains(name)) {
			inProcessPackedRefsLock.lock();
			try {
				LockFile lck = lockPackedRefsOrThrow();
				try {
					PackedRefList cur = readPackedRefs();
					int idx = cur.find(name);
					if(0 <= idx) {
						commitPackedRefs(lck, cur.remove(idx), packed, true);
					}
				} finally {
					lck.unlock();
				}
			} finally {
				inProcessPackedRefsLock.unlock();
			}
		}

		RefList<LooseRef> curLoose, newLoose;
		do {
			curLoose = looseRefs.get();
			int idx = curLoose.find(name);
			if(idx < 0)
				break;
			newLoose = curLoose.remove(idx);
		} while(!looseRefs.compareAndSet(curLoose, newLoose));

		int levels = levelsIn(name) - 2;
		delete(logFor(name), levels);
		if(dst.getStorage().isLoose()) {
			update.unlock();
			delete(fileFor(name), levels);
		}

		modCnt.incrementAndGet();
		fireRefsChanged();
	}

	public void pack(List<String> refs) throws IOException {
		pack(refs, Collections.emptyMap());
	}

	PackedRefList pack(Map<String, LockFile> heldLocks) throws IOException {
		return pack(heldLocks.keySet(), heldLocks);
	}

	private PackedRefList pack(Collection<String> refs,
							   Map<String, LockFile> heldLocks) throws IOException {
		for(LockFile ol : heldLocks.values()) {
			ol.requireLock();
		}
		if(refs.isEmpty()) {
			return null;
		}
		FS fs = parent.getFS();

		inProcessPackedRefsLock.lock();
		try {
			LockFile lck = lockPackedRefsOrThrow();
			try {
				final PackedRefList packed = getPackedRefs();
				RefList<Ref> cur = readPackedRefs();

				boolean dirty = false;
				for(String refName : refs) {
					Ref oldRef = readRef(refName, cur);
					if(oldRef == null) continue;
					if(oldRef.isSymbolic()) continue;

					Ref newRef = peeledPackedRef(oldRef);
					if(newRef == oldRef) continue;

					dirty = true;
					int idx = cur.find(refName);
					if(idx >= 0) {
						cur = cur.set(idx, newRef);
					} else {
						cur = cur.add(idx, newRef);
					}
				}
				if(!dirty) return packed;

				PackedRefList result = commitPackedRefs(lck, cur, packed,
						false);

				for(String refName : refs) {
					File refFile = fileFor(refName);
					if(!fs.exists(refFile)) continue;

					LockFile rLck = heldLocks.get(refName);
					boolean shouldUnlock;
					if(rLck == null) {
						rLck = new LockFile(refFile);
						if(!rLck.lock()) {
							continue;
						}
						shouldUnlock = true;
					} else shouldUnlock = false;

					try {
						LooseRef currentLooseRef = scanRef(null, refName);
						if(currentLooseRef == null || currentLooseRef.isSymbolic()) {
							continue;
						}
						Ref packedRef = cur.get(refName);
						ObjectId clr_oid = currentLooseRef.getObjectId();
						if(clr_oid != null && clr_oid.equals(packedRef.getObjectId())) {
							RefList<LooseRef> curLoose, newLoose;
							do {
								curLoose = looseRefs.get();
								int idx = curLoose.find(refName);
								if(idx < 0) break;
								newLoose = curLoose.remove(idx);
							} while(!looseRefs.compareAndSet(curLoose, newLoose));
							int levels = levelsIn(refName) - 2;
							delete(refFile, levels, rLck);
						}
					} finally {
						if(shouldUnlock) {
							rLck.unlock();
						}
					}
				}

				return result;
			} finally {
				lck.unlock();
			}
		} finally {
			inProcessPackedRefsLock.unlock();
		}
	}

	@Nullable
	LockFile lockPackedRefs() throws IOException {
		LockFile lck = new LockFile(packedRefsFile);
		for(int ms : getRetrySleepMs()) {
			sleep(ms);
			if(lck.lock()) {
				return lck;
			}
		}
		return null;
	}

	private LockFile lockPackedRefsOrThrow() throws IOException {
		LockFile lck = lockPackedRefs();
		if(lck == null) {
			throw new LockFailedException(packedRefsFile);
		}
		return lck;
	}

	private Ref peeledPackedRef(Ref f) throws IOException {
		if(f.getStorage().isPacked() && f.isPeeled()) {
			return f;
		}
		if(!f.isPeeled()) {
			f = peel(f);
		}
		ObjectId peeledObjectId = f.getPeeledObjectId();
		if(peeledObjectId != null) {
			return new ObjectIdRef.PeeledTag(PACKED, f.getName(),
					f.getObjectId(), peeledObjectId);
		}
		return new ObjectIdRef.PeeledNonTag(PACKED, f.getName(),
				f.getObjectId());
	}

	void log(boolean force, RefUpdate update, String msg, boolean deref)
			throws IOException {
		newLogWriter(force).log(update, msg, deref);
	}

	private Ref resolve(final Ref ref, int depth, String prefix,
						RefList<LooseRef> loose, RefList<Ref> packed) throws IOException {
		if(ref.isSymbolic()) {
			Ref dst = ref.getTarget();

			if(MAX_SYMBOLIC_REF_DEPTH <= depth) return null;

			if(loose != null && dst.getName().startsWith(prefix)) {
				int idx;
				if(0 <= (idx = loose.find(dst.getName())))
					dst = loose.get(idx);
				else if(0 <= (idx = packed.find(dst.getName())))
					dst = packed.get(idx);
				else
					return ref;
			} else {
				dst = readRef(dst.getName(), packed);
				if(dst == null)
					return ref;
			}

			dst = resolve(dst, depth + 1, prefix, loose, packed);
			if(dst == null)
				return null;
			return new SymbolicRef(ref.getName(), dst);
		}
		return ref;
	}

	PackedRefList getPackedRefs() throws IOException {
		boolean trustFolderStat = getRepository().getConfig().getBoolean(
				ConfigConstants.CONFIG_CORE_SECTION,
				ConfigConstants.CONFIG_KEY_TRUSTFOLDERSTAT, true);

		final PackedRefList curList = packedRefs.get();
		if(trustFolderStat && !curList.snapshot.isModified(packedRefsFile)) {
			return curList;
		}

		final PackedRefList newList = readPackedRefs();
		if(packedRefs.compareAndSet(curList, newList)
				&& !curList.id.equals(newList.id)) {
			modCnt.incrementAndGet();
		}
		return newList;
	}

	private PackedRefList readPackedRefs() throws IOException {
		try {
			PackedRefList result = FileUtils.readWithRetries(packedRefsFile,
					f -> {
						FileSnapshot snapshot = FileSnapshot.save(f);
						MessageDigest digest = Constants.newMessageDigest();
						try(BufferedReader br = new BufferedReader(
								new InputStreamReader(new DigestInputStream(Files.newInputStream(f.toPath()), digest), UTF_8))) {
							return new PackedRefList(parsePackedRefs(br), snapshot, ObjectId.fromRaw(digest.digest()));
						}
					});
			return result != null ? result : NO_PACKED_REFS;
		} catch(IOException e) {
			throw e;
		} catch(Exception e) {
			throw new IOException(MessageFormat.format(JGitText.get().cannotReadFile, packedRefsFile), e);
		}
	}

	private RefList<Ref> parsePackedRefs(BufferedReader br)
			throws IOException {
		RefList.Builder<Ref> all = new RefList.Builder<>();
		Ref last = null;
		boolean peeled = false;
		boolean needSort = false;

		String p;
		while((p = br.readLine()) != null) {
			if(p.charAt(0) == '#') {
				if(p.startsWith(PACKED_REFS_HEADER)) {
					p = p.substring(PACKED_REFS_HEADER.length());
					peeled = p.contains(PACKED_REFS_PEELED);
				}
				continue;
			}

			if(p.charAt(0) == '^') {
				if(last == null) throw new IOException(JGitText.get().peeledLineBeforeRef);

				ObjectId id = ObjectId.fromString(p.substring(1));
				last = new ObjectIdRef.PeeledTag(PACKED, last.getName(), last
						.getObjectId(), id);
				all.set(all.size() - 1, last);
				continue;
			}

			int sp = p.indexOf(' ');
			if(sp < 0) throw new IOException(MessageFormat.format(
					JGitText.get().packedRefsCorruptionDetected, packedRefsFile.getAbsolutePath()));

			ObjectId id = ObjectId.fromString(p.substring(0, sp));
			String name = copy(p, sp + 1, p.length());
			ObjectIdRef cur;
			if(peeled)
				cur = new ObjectIdRef.PeeledNonTag(PACKED, name, id);
			else
				cur = new ObjectIdRef.Unpeeled(PACKED, name, id);
			if(last != null && RefComparator.compareTo(last, cur) > 0)
				needSort = true;
			all.add(cur);
			last = cur;
		}

		if(needSort)
			all.sort();
		return all.toRefList();
	}

	private static String copy(String src, int off, int end) {
		return src.substring(off, end);
	}

	PackedRefList commitPackedRefs(final LockFile lck, final RefList<Ref> refs,
								   final PackedRefList oldPackedList, boolean changed) throws IOException {
		AtomicReference<PackedRefList> result = new AtomicReference<>();
		new RefWriter(refs) {
			@Override
			protected void writeFile(String name, byte[] content)
					throws IOException {
				lck.setFSync(true);
				lck.setNeedSnapshot(true);
				try {
					lck.write(content);
				} catch(IOException ioe) {
					throw new ObjectWritingException(MessageFormat.format(JGitText.get().unableToWrite, name), ioe);
				}
				try {
					lck.waitForStatChange();
				} catch(InterruptedException e) {
					lck.unlock();
					throw new ObjectWritingException(
							MessageFormat.format(
									JGitText.get().interruptedWriting, name),
							e);
				}
				if(!lck.commit())
					throw new ObjectWritingException(MessageFormat.format(JGitText.get().unableToWrite, name));

				byte[] digest = Constants.newMessageDigest().digest(content);
				PackedRefList newPackedList = new PackedRefList(
						refs, lck.getCommitSnapshot(), ObjectId.fromRaw(digest));

				PackedRefList afterUpdate = packedRefs.updateAndGet(
						p -> p.id.equals(oldPackedList.id) ? newPackedList : p);
				if(!afterUpdate.id.equals(newPackedList.id)) {
					throw new ObjectWritingException(
							MessageFormat.format(JGitText.get().unableToWrite, name));
				}
				if(changed) {
					modCnt.incrementAndGet();
				}
				result.set(newPackedList);
			}
		}.writePackedRefs();
		return result.get();
	}

	private Ref readRef(String name, RefList<Ref> packed) throws IOException {
		final RefList<LooseRef> curList = looseRefs.get();
		final int idx = curList.find(name);
		if(0 <= idx) {
			final LooseRef o = curList.get(idx);
			final LooseRef n = scanRef(o, name);
			if(n == null) {
				if(looseRefs.compareAndSet(curList, curList.remove(idx)))
					modCnt.incrementAndGet();
				return packed.get(name);
			}

			if(o == n)
				return n;
			if(looseRefs.compareAndSet(curList, curList.set(idx, n)))
				modCnt.incrementAndGet();
			return n;
		}

		final LooseRef n = scanRef(null, name);
		if(n == null) return packed.get(name);

		for(String additionalRefsName : additionalRefsNames) {
			if(name.equals(additionalRefsName)) return n;
		}

		if(looseRefs.compareAndSet(curList, curList.add(idx, n)))
			modCnt.incrementAndGet();
		return n;
	}

	LooseRef scanRef(LooseRef ref, String name) throws IOException {
		final File path = fileFor(name);
		FileSnapshot currentSnapshot = null;

		if(ref != null) {
			currentSnapshot = ref.getSnapShot();
			if(!currentSnapshot.isModified(path))
				return ref;
			name = ref.getName();
		}

		final int limit = 4096;

		class LooseItems {
			final FileSnapshot snapshot;

			final byte[] buf;

			LooseItems(FileSnapshot snapshot, byte[] buf) {
				this.snapshot = snapshot;
				this.buf = buf;
			}
		}
		LooseItems loose;
		try {
			loose = FileUtils.readWithRetries(path,
					f -> new LooseItems(FileSnapshot.save(f),
							IO.readSome(f, limit)));
		} catch(IOException e) {
			throw e;
		} catch(Exception e) {
			throw new IOException(
					MessageFormat.format(JGitText.get().cannotReadFile, path),
					e);
		}

		if(loose == null) return null;
		int n = loose.buf.length;
		if(n == 0) return null;

		if(isSymRef(loose.buf, n)) {
			if(n == limit) return null;

			while(0 < n && Character.isWhitespace(loose.buf[n - 1]))
				n--;
			if(n < 6) {
				String content = RawParseUtils.decode(loose.buf, 0, n);
				throw new IOException(MessageFormat.format(JGitText.get().notARef, name, content));
			}
			final String target = RawParseUtils.decode(loose.buf, 5, n);
			if(ref != null && ref.isSymbolic()
					&& ref.getTarget().getName().equals(target)) {
				currentSnapshot.setClean(loose.snapshot);
				return ref;
			}
			return newSymbolicRef(loose.snapshot, name, target);
		}

		if(n < OBJECT_ID_STRING_LENGTH) return null;

		final ObjectId id;
		try {
			id = ObjectId.fromString(loose.buf, 0);
			if(ref != null && !ref.isSymbolic()
					&& id.equals(ref.getTarget().getObjectId())) {
				currentSnapshot.setClean(loose.snapshot);
				return ref;
			}

		} catch(IllegalArgumentException notRef) {
			while(0 < n && Character.isWhitespace(loose.buf[n - 1]))
				n--;
			String content = RawParseUtils.decode(loose.buf, 0, n);

			throw new IOException(MessageFormat.format(JGitText.get().notARef,
					name, content), notRef);
		}
		return new LooseUnpeeled(loose.snapshot, name, id);
	}

	private static boolean isSymRef(byte[] buf, int n) {
		if(n < 6)
			return false;
		return buf[0] == 'r'
				&& buf[1] == 'e'
				&& buf[2] == 'f'
				&& buf[3] == ':'
				&& buf[4] == ' ';
	}

	boolean isInClone() throws IOException {
		return hasDanglingHead() && !packedRefsFile.exists() && !hasLooseRef();
	}

	private boolean hasDanglingHead() throws IOException {
		Ref head = exactRef(Constants.HEAD);
		if(head != null) {
			ObjectId id = head.getObjectId();
			return id == null || id.equals(ObjectId.zeroId());
		}
		return false;
	}

	private boolean hasLooseRef() throws IOException {
		try(Stream<Path> stream = Files.walk(refsDir.toPath())) {
			return stream.anyMatch(Files::isRegularFile);
		}
	}

	void fireRefsChanged() {
		final int last = lastNotifiedModCnt.get();
		final int curr = modCnt.get();
		if(last != curr && lastNotifiedModCnt.compareAndSet(last, curr) && last != 0)
			parent.fireEvent(new RefsChangedEvent());
	}

	File fileFor(String name) {
		if(name.startsWith(R_REFS)) {
			name = name.substring(R_REFS.length());
			return new File(refsDir, name);
		}
		return new File(gitDir, name);
	}

	static int levelsIn(String name) {
		int count = 0;
		for(int p = name.indexOf('/'); p >= 0; p = name.indexOf('/', p + 1))
			count++;
		return count;
	}

	static void delete(File file, int depth) throws IOException {
		delete(file, depth, null);
	}

	private static void delete(File file, int depth, LockFile rLck)
			throws IOException {
		if(!file.delete() && file.isFile()) {
			throw new IOException(MessageFormat.format(
					JGitText.get().fileCannotBeDeleted, file));
		}

		if(rLck != null) {
			rLck.unlock();
		}
		File dir = file.getParentFile();
		for(int i = 0; i < depth; ++i) {
			try {
				Files.deleteIfExists(dir.toPath());
			} catch(DirectoryNotEmptyException e) {
				break;
			} catch(IOException e) {
				LOG.warn(MessageFormat.format(JGitText.get().unableToRemovePath,
						dir), e);
				break;
			}
			dir = dir.getParentFile();
		}
	}

	Iterable<Integer> getRetrySleepMs() {
		return retrySleepMs;
	}

	static void sleep(long ms) throws InterruptedIOException {
		if(ms <= 0) {
			return;
		}
		try {
			Thread.sleep(ms);
		} catch(InterruptedException e) {
			InterruptedIOException ie = new InterruptedIOException();
			ie.initCause(e);
			throw ie;
		}
	}

	static class PackedRefList extends RefList<Ref> {

		private final FileSnapshot snapshot;
		private final ObjectId id;

		private PackedRefList(RefList<Ref> src, FileSnapshot s, ObjectId i) {
			super(src);
			snapshot = s;
			id = i;
		}
	}

	private static final PackedRefList NO_PACKED_REFS = new PackedRefList(
			RefList.emptyList(), FileSnapshot.MISSING_FILE, ObjectId.zeroId());

	private static LooseSymbolicRef newSymbolicRef(FileSnapshot snapshot, String name, String target) {
		Ref dst = new ObjectIdRef.Unpeeled(NEW, target, null);
		return new LooseSymbolicRef(snapshot, name, dst);
	}

	private interface LooseRef extends Ref {
		FileSnapshot getSnapShot();

		LooseRef peel(ObjectIdRef newLeaf);
	}

	private static final class LoosePeeledTag extends ObjectIdRef.PeeledTag
			implements LooseRef {
		private final FileSnapshot snapShot;

		LoosePeeledTag(FileSnapshot snapshot, @NonNull String refName,
					   @NonNull ObjectId id, @NonNull ObjectId p) {
			super(LOOSE, refName, id, p);
			this.snapShot = snapshot;
		}

		@Override
		public FileSnapshot getSnapShot() {
			return snapShot;
		}

		@Override
		public LooseRef peel(ObjectIdRef newLeaf) {
			return this;
		}
	}

	private static final class LooseNonTag extends ObjectIdRef.PeeledNonTag
			implements LooseRef {
		private final FileSnapshot snapShot;

		LooseNonTag(FileSnapshot snapshot, @NonNull String refName,
					@NonNull ObjectId id) {
			super(LOOSE, refName, id);
			this.snapShot = snapshot;
		}

		@Override
		public FileSnapshot getSnapShot() {
			return snapShot;
		}

		@Override
		public LooseRef peel(ObjectIdRef newLeaf) {
			return this;
		}
	}

	private static final class LooseUnpeeled extends ObjectIdRef.Unpeeled
			implements LooseRef {
		private final FileSnapshot snapShot;

		LooseUnpeeled(FileSnapshot snapShot, @NonNull String refName,
					  @NonNull ObjectId id) {
			super(LOOSE, refName, id);
			this.snapShot = snapShot;
		}

		@Override
		public FileSnapshot getSnapShot() {
			return snapShot;
		}

		@NonNull
		@Override
		public ObjectId getObjectId() {
			ObjectId id = super.getObjectId();
			assert id != null;
			return id;
		}

		@Override
		public LooseRef peel(ObjectIdRef newLeaf) {
			ObjectId peeledObjectId = newLeaf.getPeeledObjectId();
			ObjectId objectId = getObjectId();
			if(peeledObjectId != null) {
				return new LoosePeeledTag(snapShot, getName(),
						objectId, peeledObjectId);
			}
			return new LooseNonTag(snapShot, getName(), objectId);
		}
	}

	private static final class LooseSymbolicRef extends SymbolicRef implements
			LooseRef {
		private final FileSnapshot snapShot;

		LooseSymbolicRef(FileSnapshot snapshot, @NonNull String refName,
						 @NonNull Ref target) {
			super(refName, target);
			this.snapShot = snapshot;
		}

		@Override
		public FileSnapshot getSnapShot() {
			return snapShot;
		}

		@Override
		public LooseRef peel(ObjectIdRef newLeaf) {
			throw new UnsupportedOperationException();
		}
	}
}
