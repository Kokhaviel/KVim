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

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.internal.storage.pack.ObjectToPack;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.eclipse.jgit.internal.storage.pack.PackWriter;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.lib.RepositoryCache.FileKey;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FileUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.jgit.internal.storage.pack.PackExt.*;

public class ObjectDirectory extends FileObjectDatabase {

	private static final int RESOLVE_ABBREV_LIMIT = 256;

	private final AlternateHandle handle = new AlternateHandle(this);
	private final Config config;
	private final File objects;
	private final File infoDirectory;
	private final LooseObjects loose;
	private final PackDirectory packed;
	private final PackDirectory preserved;
	private final File alternatesFile;
	private final FS fs;
	private final AtomicReference<AlternateHandle[]> alternates;
	private final File shallowFile;
	private FileSnapshot shallowFileSnapshot = FileSnapshot.DIRTY;
	private Set<ObjectId> shallowCommitsIds;

	public ObjectDirectory(final Config cfg, final File dir,
						   File[] alternatePaths, FS fs, File shallowFile) throws IOException {
		config = cfg;
		objects = dir;
		infoDirectory = new File(objects, "info");
		File packDirectory = new File(objects, "pack");
		File preservedDirectory = new File(packDirectory, "preserved");
		alternatesFile = new File(objects, Constants.INFO_ALTERNATES);
		loose = new LooseObjects(objects);
		packed = new PackDirectory(config, packDirectory);
		preserved = new PackDirectory(config, preservedDirectory);
		this.fs = fs;
		this.shallowFile = shallowFile;

		alternates = new AtomicReference<>();
		if(alternatePaths != null) {
			AlternateHandle[] alt;

			alt = new AlternateHandle[alternatePaths.length];
			for(int i = 0; i < alternatePaths.length; i++)
				alt[i] = openAlternate(alternatePaths[i]);
			alternates.set(alt);
		}
	}

	@Override
	public final File getDirectory() {
		return loose.getDirectory();
	}

	public final File getPackDirectory() {
		return packed.getDirectory();
	}

	public final File getPreservedDirectory() {
		return preserved.getDirectory();
	}

	@Override
	public boolean exists() {
		return fs.exists(objects);
	}

	@Override
	public void create() throws IOException {
		loose.create();
		FileUtils.mkdir(infoDirectory);
		packed.create();
	}

	@Override
	public ObjectDirectoryInserter newInserter() {
		return new ObjectDirectoryInserter(this, config);
	}

	@Override
	public void close() {
		loose.close();

		packed.close();

		AlternateHandle[] alt = alternates.get();
		if(alt != null && alternates.compareAndSet(alt, null)) {
			for(AlternateHandle od : alt)
				od.close();
		}
	}

	@Override
	public Collection<Pack> getPacks() {
		return packed.getPacks();
	}

	@Override
	public Pack openPack(File pack) throws IOException {
		PackFile pf;
		try {
			pf = new PackFile(pack);
		} catch(IllegalArgumentException e) {
			throw new IOException(
					MessageFormat.format(JGitText.get().notAValidPack, pack),
					e);
		}

		String p = pf.getName();
		if(p.length() != 50 || !p.startsWith("pack-")
				|| !pf.getPackExt().equals(PACK)) {
			throw new IOException(
					MessageFormat.format(JGitText.get().notAValidPack, pack));
		}

		PackFile bitmapIdx = pf.create(BITMAP_INDEX);
		Pack res = new Pack(pack, bitmapIdx.exists() ? bitmapIdx : null);
		packed.insert(res);
		return res;
	}

	@Override
	public String toString() {
		return "ObjectDirectory[" + getDirectory() + "]";
	}

	@Override
	public boolean has(AnyObjectId objectId) {
		return loose.hasCached(objectId)
				|| hasPackedOrLooseInSelfOrAlternate(objectId)
				|| (restoreFromSelfOrAlternate(objectId, null)
				&& hasPackedOrLooseInSelfOrAlternate(objectId));
	}

	private boolean hasPackedOrLooseInSelfOrAlternate(AnyObjectId objectId) {
		return hasPackedInSelfOrAlternate(objectId, null)
				|| hasLooseInSelfOrAlternate(objectId, null);
	}

	private boolean hasPackedInSelfOrAlternate(AnyObjectId objectId,
											   Set<AlternateHandle.Id> skips) {
		if(hasPackedObject(objectId)) {
			return true;
		}
		skips = addMe(skips);
		for(AlternateHandle alt : myAlternates()) {
			if(!skips.contains(alt.getId())) {
				if(alt.db.hasPackedInSelfOrAlternate(objectId, skips)) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean hasLooseInSelfOrAlternate(AnyObjectId objectId,
											  Set<AlternateHandle.Id> skips) {
		if(loose.has(objectId)) {
			return true;
		}
		skips = addMe(skips);
		for(AlternateHandle alt : myAlternates()) {
			if(!skips.contains(alt.getId())) {
				if(alt.db.hasLooseInSelfOrAlternate(objectId, skips)) {
					return true;
				}
			}
		}
		return false;
	}

	boolean hasPackedObject(AnyObjectId objectId) {
		return packed.has(objectId);
	}

	@Override
	void resolve(Set<ObjectId> matches, AbbreviatedObjectId id)
			throws IOException {
		resolve(matches, id, null);
	}

	private void resolve(Set<ObjectId> matches, AbbreviatedObjectId id, Set<AlternateHandle.Id> skips) {
		if(!packed.resolve(matches, id, RESOLVE_ABBREV_LIMIT))
			return;

		if(!loose.resolve(matches, id, RESOLVE_ABBREV_LIMIT))
			return;

		skips = addMe(skips);
		for(AlternateHandle alt : myAlternates()) {
			if(!skips.contains(alt.getId())) {
				alt.db.resolve(matches, id, skips);
				if(matches.size() > RESOLVE_ABBREV_LIMIT) {
					return;
				}
			}
		}
	}

	@Override
	ObjectLoader openObject(WindowCursor curs, AnyObjectId objectId)
			throws IOException {
		ObjectLoader ldr = openObjectWithoutRestoring(curs, objectId);
		if(ldr == null && restoreFromSelfOrAlternate(objectId, null)) {
			ldr = openObjectWithoutRestoring(curs, objectId);
		}
		return ldr;
	}

	private ObjectLoader openObjectWithoutRestoring(WindowCursor curs, AnyObjectId objectId)
			throws IOException {
		if(loose.hasCached(objectId)) {
			ObjectLoader ldr = openLooseObject(curs, objectId);
			if(ldr != null) {
				return ldr;
			}
		}
		ObjectLoader ldr = openPackedFromSelfOrAlternate(curs, objectId, null);
		if(ldr != null) {
			return ldr;
		}
		return openLooseFromSelfOrAlternate(curs, objectId, null);
	}

	private ObjectLoader openPackedFromSelfOrAlternate(WindowCursor curs,
													   AnyObjectId objectId, Set<AlternateHandle.Id> skips) {
		ObjectLoader ldr = openPackedObject(curs, objectId);
		if(ldr != null) {
			return ldr;
		}
		skips = addMe(skips);
		for(AlternateHandle alt : myAlternates()) {
			if(!skips.contains(alt.getId())) {
				ldr = alt.db.openPackedFromSelfOrAlternate(curs, objectId, skips);
				if(ldr != null) {
					return ldr;
				}
			}
		}
		return null;
	}

	private ObjectLoader openLooseFromSelfOrAlternate(WindowCursor curs,
													  AnyObjectId objectId, Set<AlternateHandle.Id> skips)
			throws IOException {
		ObjectLoader ldr = openLooseObject(curs, objectId);
		if(ldr != null) {
			return ldr;
		}
		skips = addMe(skips);
		for(AlternateHandle alt : myAlternates()) {
			if(!skips.contains(alt.getId())) {
				ldr = alt.db.openLooseFromSelfOrAlternate(curs, objectId, skips);
				if(ldr != null) {
					return ldr;
				}
			}
		}
		return null;
	}

	ObjectLoader openPackedObject(WindowCursor curs, AnyObjectId objectId) {
		return packed.open(curs, objectId);
	}

	@Override
	ObjectLoader openLooseObject(WindowCursor curs, AnyObjectId id)
			throws IOException {
		return loose.open(curs, id);
	}

	@Override
	long getObjectSize(WindowCursor curs, AnyObjectId id) throws IOException {
		long sz = getObjectSizeWithoutRestoring(curs, id);
		if(0 > sz && restoreFromSelfOrAlternate(id, null)) {
			sz = getObjectSizeWithoutRestoring(curs, id);
		}
		return sz;
	}

	private long getObjectSizeWithoutRestoring(WindowCursor curs,
											   AnyObjectId id) throws IOException {
		if(loose.hasCached(id)) {
			long len = loose.getSize(curs, id);
			if(0 <= len) {
				return len;
			}
		}
		long len = getPackedSizeFromSelfOrAlternate(curs, id, null);
		if(0 <= len) {
			return len;
		}
		return getLooseSizeFromSelfOrAlternate(curs, id, null);
	}

	private long getPackedSizeFromSelfOrAlternate(WindowCursor curs,
												  AnyObjectId id, Set<AlternateHandle.Id> skips) {
		long len = packed.getSize(curs, id);
		if(0 <= len) {
			return len;
		}
		skips = addMe(skips);
		for(AlternateHandle alt : myAlternates()) {
			if(!skips.contains(alt.getId())) {
				len = alt.db.getPackedSizeFromSelfOrAlternate(curs, id, skips);
				if(0 <= len) {
					return len;
				}
			}
		}
		return -1;
	}

	private long getLooseSizeFromSelfOrAlternate(WindowCursor curs,
												 AnyObjectId id, Set<AlternateHandle.Id> skips) throws IOException {
		long len = loose.getSize(curs, id);
		if(0 <= len) {
			return len;
		}
		skips = addMe(skips);
		for(AlternateHandle alt : myAlternates()) {
			if(!skips.contains(alt.getId())) {
				len = alt.db.getLooseSizeFromSelfOrAlternate(curs, id, skips);
				if(0 <= len) {
					return len;
				}
			}
		}
		return -1;
	}

	@Override
	void selectObjectRepresentation(PackWriter packer, ObjectToPack otp,
									WindowCursor curs) {
		selectObjectRepresentation(packer, otp, curs, null);
	}

	private void selectObjectRepresentation(PackWriter packer, ObjectToPack otp,
											WindowCursor curs, Set<AlternateHandle.Id> skips) {
		packed.selectRepresentation(packer, otp, curs);

		skips = addMe(skips);
		for(AlternateHandle h : myAlternates()) {
			if(!skips.contains(h.getId())) {
				h.db.selectObjectRepresentation(packer, otp, curs, skips);
			}
		}
	}

	private boolean restoreFromSelfOrAlternate(AnyObjectId objectId,
											   Set<AlternateHandle.Id> skips) {
		if(restoreFromSelf(objectId)) {
			return true;
		}

		skips = addMe(skips);
		for(AlternateHandle alt : myAlternates()) {
			if(!skips.contains(alt.getId())) {
				if(alt.db.restoreFromSelfOrAlternate(objectId, skips)) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean restoreFromSelf(AnyObjectId objectId) {
		Pack preservedPack = preserved.getPack(objectId);
		if(preservedPack == null) {
			return false;
		}
		PackFile preservedFile = new PackFile(preservedPack.getPackFile());
		for(PackExt ext : PackExt.values()) {
			if(!INDEX.equals(ext)) {
				restore(preservedFile.create(ext));
			}
		}
		restore(preservedFile.create(INDEX));
		return true;
	}

	private boolean restore(PackFile preservedPack) {
		PackFile restored = preservedPack
				.createForDirectory(packed.getDirectory());
		try {
			Files.createLink(restored.toPath(), preservedPack.toPath());
		} catch(IOException e) {
			return false;
		}
		return true;
	}

	@Override
	InsertLooseObjectResult insertUnpackedObject(File tmp, ObjectId id,
												 boolean createDuplicate) throws IOException {
		if(loose.hasCached(id)) {
			FileUtils.delete(tmp, FileUtils.RETRY);
			return InsertLooseObjectResult.EXISTS_LOOSE;
		}
		if(!createDuplicate && has(id)) {
			FileUtils.delete(tmp, FileUtils.RETRY);
			return InsertLooseObjectResult.EXISTS_PACKED;
		}
		return loose.insert(tmp, id);
	}

	@Override
	Config getConfig() {
		return config;
	}

	@Override
	FS getFS() {
		return fs;
	}

	@Override
	Set<ObjectId> getShallowCommits() throws IOException {
		if(shallowFile == null || !shallowFile.isFile())
			return Collections.emptySet();

		if(shallowFileSnapshot == null
				|| shallowFileSnapshot.isModified(shallowFile)) {
			shallowCommitsIds = new HashSet<>();

			try(BufferedReader reader = open(shallowFile)) {
				String line;
				while((line = reader.readLine()) != null) {
					try {
						shallowCommitsIds.add(ObjectId.fromString(line));
					} catch(IllegalArgumentException ex) {
						throw new IOException(MessageFormat
								.format(JGitText.get().badShallowLine, line),
								ex);
					}
				}
			}

			shallowFileSnapshot = FileSnapshot.save(shallowFile);
		}

		return shallowCommitsIds;
	}

	void closeAllPackHandles(File packFile) {
		if(packFile.exists()) {
			for(Pack p : packed.getPacks()) {
				if(packFile.getPath().equals(p.getPackFile().getPath())) {
					p.close();
					break;
				}
			}
		}
	}

	AlternateHandle[] myAlternates() {
		AlternateHandle[] alt = alternates.get();
		if(alt == null) {
			synchronized(alternates) {
				alt = alternates.get();
				if(alt == null) {
					try {
						alt = loadAlternates();
					} catch(IOException e) {
						alt = new AlternateHandle[0];
					}
					alternates.set(alt);
				}
			}
		}
		return alt;
	}

	Set<AlternateHandle.Id> addMe(Set<AlternateHandle.Id> skips) {
		if(skips == null) {
			skips = new HashSet<>();
		}
		skips.add(handle.getId());
		return skips;
	}

	private AlternateHandle[] loadAlternates() throws IOException {
		final List<AlternateHandle> l = new ArrayList<>(4);
		try(BufferedReader br = open(alternatesFile)) {
			String line;
			while((line = br.readLine()) != null) {
				l.add(openAlternate(line));
			}
		}
		return l.toArray(new AlternateHandle[0]);
	}

	private static BufferedReader open(File f) throws IOException {
		return Files.newBufferedReader(f.toPath(), UTF_8);
	}

	private AlternateHandle openAlternate(String location)
			throws IOException {
		final File objdir = fs.resolve(objects, location);
		return openAlternate(objdir);
	}

	private AlternateHandle openAlternate(File objdir) throws IOException {
		final File parent = objdir.getParentFile();
		if(FileKey.isGitRepository(parent, fs)) {
			FileKey key = FileKey.exact(parent, fs);
			FileRepository db = (FileRepository) RepositoryCache.open(key);
			return new AlternateRepository(db);
		}

		ObjectDirectory db = new ObjectDirectory(config, objdir, null, fs, null);
		return new AlternateHandle(db);
	}

	@Override
	public File fileFor(AnyObjectId objectId) {
		return loose.fileFor(objectId);
	}

	static class AlternateHandle {
		static class Id {
			String alternateId;

			public Id(File object) {
				try {
					this.alternateId = object.getCanonicalPath();
				} catch(Exception e) {
					alternateId = null;
				}
			}

			@Override
			public boolean equals(Object o) {
				if(o == this) {
					return true;
				}
				if(!(o instanceof Id)) {
					return false;
				}
				Id aId = (Id) o;
				return Objects.equals(alternateId, aId.alternateId);
			}

			@Override
			public int hashCode() {
				if(alternateId == null) {
					return 1;
				}
				return alternateId.hashCode();
			}
		}

		final ObjectDirectory db;

		AlternateHandle(ObjectDirectory db) {
			this.db = db;
		}

		void close() {
			db.close();
		}

		public Id getId() {
			return db.getAlternateId();
		}
	}

	static class AlternateRepository extends AlternateHandle {
		final FileRepository repository;

		AlternateRepository(FileRepository r) {
			super(r.getObjectDatabase());
			repository = r;
		}

		@Override
		void close() {
			repository.close();
		}
	}

	@Override
	public ObjectDatabase newCachedDatabase() {
		return newCachedFileObjectDatabase();
	}

	CachedObjectDirectory newCachedFileObjectDatabase() {
		return new CachedObjectDirectory(this);
	}

	AlternateHandle.Id getAlternateId() {
		return new AlternateHandle.Id(objects);
	}
}
