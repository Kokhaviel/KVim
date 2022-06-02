/*
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org>
 * Copyright (C) 2010, Christian Halstrick <christian.halstrick@sap.com>
 * Copyright (C) 2010, Matthias Sohn <matthias.sohn@sap.com>
 * Copyright (C) 2012, 2022, Robin Rosenberg and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.treewalk;

import org.eclipse.jgit.api.errors.FilterFailedException;
import org.eclipse.jgit.attributes.AttributesNode;
import org.eclipse.jgit.attributes.FilterCommandRegistry;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.errors.*;
import org.eclipse.jgit.ignore.IgnoreNode;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.lib.CoreConfig.*;
import org.eclipse.jgit.submodule.SubmoduleWalk;
import org.eclipse.jgit.treewalk.TreeWalk.OperationType;
import org.eclipse.jgit.util.*;
import org.eclipse.jgit.util.FS.ExecutionResult;
import org.eclipse.jgit.util.TemporaryBuffer.LocalFile;
import org.eclipse.jgit.util.io.EolStreamTypeUtil;
import org.eclipse.jgit.util.sha1.SHA1;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.*;

import static java.nio.charset.StandardCharsets.UTF_8;

public abstract class WorkingTreeIterator extends AbstractTreeIterator {

	private static final int MAX_EXCEPTION_TEXT_SIZE = 10 * 1024;
	protected static final Entry[] EOF = {};
	static final int BUFFER_SIZE = 2048;
	private static final long MAXIMUM_FILE_SIZE_TO_READ_FULLY = 65536;
	private final IteratorState state;
	private byte[] contentId;
	private int contentIdFromPtr;
	private Entry[] entries;
	private int entryCnt;
	private int ptr;
	private IgnoreNode ignoreNode;
	private Holder<String> cleanFilterCommandHolder;
	private Holder<EolStreamType> eolStreamTypeHolder;
	protected Repository repository;
	private long canonLen = -1;
	private int contentIdOffset;
	private final InstantComparator timestampComparator = new InstantComparator();

	protected WorkingTreeIterator(WorkingTreeOptions options) {
		super();
		state = new IteratorState(options);
	}

	protected WorkingTreeIterator(final String prefix,
								  WorkingTreeOptions options) {
		super(prefix);
		state = new IteratorState(options);
	}

	protected WorkingTreeIterator(WorkingTreeIterator p) {
		super(p);
		state = p.state;
		repository = p.repository;
	}

	protected void initRootIterator(Repository repo) {
		repository = repo;
		Entry entry;
		if(ignoreNode instanceof PerDirectoryIgnoreNode)
			entry = ((PerDirectoryIgnoreNode) ignoreNode).entry;
		else
			entry = null;
		ignoreNode = new RootIgnoreNode(entry, repo);
	}

	public void setDirCacheIterator(TreeWalk walk, int treeId) {
		state.walk = walk;
		state.dirCacheTree = treeId;
	}

	protected DirCacheIterator getDirCacheIterator() {
		if(state.dirCacheTree >= 0 && state.walk != null) {
			return state.walk.getTree(state.dirCacheTree
			);
		}
		return null;
	}

	public void setWalkIgnoredDirectories(boolean includeIgnored) {
		state.walkIgnored = includeIgnored;
	}

	public boolean walksIgnoredDirectories() {
		return state.walkIgnored;
	}

	@Override
	public boolean hasId() {
		if(contentIdFromPtr == ptr)
			return true;
		return (mode & FileMode.TYPE_MASK) == FileMode.TYPE_FILE;
	}

	@Override
	public byte[] idBuffer() {
		if(contentIdFromPtr == ptr)
			return contentId;

		if(state.walk != null) {
			DirCacheIterator i = state.walk.getTree(state.dirCacheTree
			);
			if(i != null) {
				DirCacheEntry ent = i.getDirCacheEntry();
				if(ent != null && compareMetadata(ent) == MetadataDiff.EQUAL
						&& ((ent.getFileMode().getBits()
						& FileMode.TYPE_MASK) != FileMode.TYPE_GITLINK)) {
					contentIdOffset = i.idOffset();
					contentIdFromPtr = ptr;
					return contentId = i.idBuffer();
				}
			}
			contentIdOffset = 0;
		}
		switch(mode & FileMode.TYPE_MASK) {
			case FileMode.TYPE_SYMLINK:
			case FileMode.TYPE_FILE:
				contentIdFromPtr = ptr;
				return contentId = idBufferBlob(entries[ptr]);
			case FileMode.TYPE_GITLINK:
				contentIdFromPtr = ptr;
				return contentId = idSubmodule(entries[ptr]);
		}
		return zeroid;
	}

	@Override
	public boolean isWorkTree() {
		return true;
	}

	protected byte[] idSubmodule(Entry e) {
		if(repository == null)
			return zeroid;
		File directory;
		try {
			directory = repository.getWorkTree();
		} catch(NoWorkTreeException nwte) {
			return zeroid;
		}
		return idSubmodule(directory, e);
	}

	protected byte[] idSubmodule(File directory, Entry e) {
		try(Repository submoduleRepo = SubmoduleWalk.getSubmoduleRepository(
				directory, e.getName(),
				repository != null ? repository.getFS() : FS.DETECTED)) {
			if(submoduleRepo == null) {
				return zeroid;
			}
			ObjectId head = submoduleRepo.resolve(Constants.HEAD);
			if(head == null) {
				return zeroid;
			}
			byte[] id = new byte[Constants.OBJECT_ID_LENGTH];
			head.copyRawTo(id, 0);
			return id;
		} catch(IOException exception) {
			return zeroid;
		}
	}

	private static final byte[] digits = {'0', '1', '2', '3', '4', '5', '6',
			'7', '8', '9'};

	private static final byte[] hblob = Constants
			.encodedTypeString(Constants.OBJ_BLOB);

	private byte[] idBufferBlob(Entry e) {
		try {
			final InputStream is = e.openInputStream();
			if(is == null)
				return zeroid;
			try {
				state.initializeReadBuffer();

				final long len = e.getLength();
				InputStream filteredIs = possiblyFilteredInputStream(e, is,
						len);
				return computeHash(filteredIs, canonLen);
			} finally {
				safeClose(is);
			}
		} catch(IOException err) {
			return zeroid;
		}
	}

	private InputStream possiblyFilteredInputStream(final Entry e,
													final InputStream is, final long len)
			throws IOException {
		if(getCleanFilterCommand() == null
				&& getEolStreamType(
		) == EolStreamType.DIRECT) {
			canonLen = len;
			return is;
		}

		if(len <= MAXIMUM_FILE_SIZE_TO_READ_FULLY) {
			ByteBuffer rawbuf = IO.readWholeStream(is, (int) len);
			rawbuf = filterClean(rawbuf.array(), rawbuf.limit());
			canonLen = rawbuf.limit();
			return new ByteArrayInputStream(rawbuf.array(), 0, (int) canonLen);
		}

		if(getCleanFilterCommand() == null && isBinary(e)) {
			canonLen = len;
			return is;
		}

		final InputStream lenIs = filterClean(e.openInputStream());
		try {
			canonLen = computeLength(lenIs);
		} finally {
			safeClose(lenIs);
		}
		return filterClean(is);
	}

	private static void safeClose(InputStream in) {
		try {
			in.close();
		} catch(IOException ignored) {
		}
	}

	private static boolean isBinary(Entry entry) throws IOException {
		InputStream in = entry.openInputStream();
		try {
			return RawText.isBinary(in);
		} finally {
			safeClose(in);
		}
	}

	private ByteBuffer filterClean(byte[] src, int n)
			throws IOException {
		InputStream in = new ByteArrayInputStream(src);
		try {
			return IO.readWholeStream(filterClean(in), n);
		} finally {
			safeClose(in);
		}
	}

	private InputStream filterClean(InputStream in)
			throws IOException {
		in = EolStreamTypeUtil.wrapInputStream(in,
				getEolStreamType());
		String filterCommand = getCleanFilterCommand();
		if(filterCommand != null) {
			if(FilterCommandRegistry.isRegistered(filterCommand)) {
				LocalFile buffer = new TemporaryBuffer.LocalFile(null);
				FilterCommandRegistry.createFilterCommand(filterCommand, repository, in, buffer);
				return buffer.openInputStreamWithAutoDestroy();
			}
			FS fs = repository.getFS();
			ProcessBuilder filterProcessBuilder = fs.runInShell(filterCommand,
					new String[0]);
			filterProcessBuilder.directory(repository.getWorkTree());
			filterProcessBuilder.environment().put(Constants.GIT_DIR_KEY,
					repository.getDirectory().getAbsolutePath());
			ExecutionResult result;
			try {
				result = fs.execute(filterProcessBuilder, in);
			} catch(IOException | InterruptedException e) {
				throw new IOException(new FilterFailedException(e,
						filterCommand, getEntryPathString()));
			}
			int rc = result.getRc();
			if(rc != 0) {
				throw new IOException(new FilterFailedException(rc,
						filterCommand, getEntryPathString(),
						result.getStdout().toByteArray(MAX_EXCEPTION_TEXT_SIZE),
						result.getStderr().toString(MAX_EXCEPTION_TEXT_SIZE)));
			}
			return result.getStdout().openInputStreamWithAutoDestroy();
		}
		return in;
	}

	public WorkingTreeOptions getOptions() {
		return state.options;
	}

	public Repository getRepository() {
		return repository;
	}

	@Override
	public int idOffset() {
		return contentIdOffset;
	}

	@Override
	public void reset() {
		if(!first()) {
			ptr = 0;
			if(!eof())
				parseEntry();
		}
	}

	@Override
	public boolean first() {
		return ptr == 0;
	}

	@Override
	public boolean eof() {
		return ptr == entryCnt;
	}

	@Override
	public void next(int delta) throws CorruptObjectException {
		ptr += delta;
		if(!eof()) {
			parseEntry();
		}
	}

	@Override
	public void back(int delta) {
		ptr -= delta;
		parseEntry();
	}

	private void parseEntry() {
		final Entry e = entries[ptr];
		mode = e.getMode().getBits();

		final int nameLen = e.encodedNameLen;
		ensurePathCapacity(pathOffset + nameLen, pathOffset);
		System.arraycopy(e.encodedName, 0, path, pathOffset, nameLen);
		pathLen = pathOffset + nameLen;
		canonLen = -1;
		cleanFilterCommandHolder = null;
		eolStreamTypeHolder = null;
	}

	public long getEntryLength() {
		return current().getLength();
	}

	public long getEntryContentLength() throws IOException {
		if(canonLen == -1) {
			long rawLen = getEntryLength();
			if(rawLen == 0)
				canonLen = 0;
			InputStream is = current().openInputStream();
			try {
				possiblyFilteredInputStream(current(), is, current()
						.getLength());
			} finally {
				safeClose(is);
			}
		}
		return canonLen;
	}

	public Instant getEntryLastModifiedInstant() {
		return current().getLastModifiedInstant();
	}

	public InputStream openEntryStream() throws IOException {
		InputStream rawis = current().openInputStream();
		if(getCleanFilterCommand() == null
				&& getEolStreamType(
		) == EolStreamType.DIRECT) {
			return rawis;
		}
		return filterClean(rawis);
	}

	public boolean isEntryIgnored() throws IOException {
		return isEntryIgnored(pathLen);
	}

	protected boolean isEntryIgnored(int pLen) throws IOException {
		return isEntryIgnored(pLen, mode);
	}

	private boolean isEntryIgnored(int pLen, int fileMode)
			throws IOException {
		final int pOff = 0 < pathOffset ? pathOffset - 1 : pathOffset;
		String pathRel = TreeWalk.pathOf(this.path, pOff, pLen);
		String parentRel = getParentPath(pathRel);

		if(isDirectoryIgnored(parentRel)) {
			return true;
		}

		IgnoreNode rules = getIgnoreNode();
		final Boolean ignored = rules != null
				? rules.checkIgnored(pathRel, FileMode.TREE.equals(fileMode))
				: null;
		if(ignored != null) {
			return ignored;
		}
		return parent instanceof WorkingTreeIterator
				&& ((WorkingTreeIterator) parent).isEntryIgnored(pLen,
				fileMode);
	}

	private IgnoreNode getIgnoreNode() throws IOException {
		if(ignoreNode instanceof PerDirectoryIgnoreNode)
			ignoreNode = ((PerDirectoryIgnoreNode) ignoreNode).load();
		return ignoreNode;
	}

	public AttributesNode getEntryAttributesNode() throws IOException {
		if(attributesNode instanceof PerDirectoryAttributesNode)
			attributesNode = ((PerDirectoryAttributesNode) attributesNode)
					.load();
		return attributesNode;
	}

	private static final Comparator<Entry> ENTRY_CMP = (Entry a,
														Entry b) -> Paths.compare(a.encodedName, 0, a.encodedNameLen,
			a.getMode().getBits(), b.encodedName, 0, b.encodedNameLen,
			b.getMode().getBits());

	protected void init(Entry[] list) {
		entries = list;
		int i, o;

		final CharsetEncoder nameEncoder = state.nameEncoder;
		for(i = 0, o = 0; i < entries.length; i++) {
			final Entry e = entries[i];
			if(e == null)
				continue;
			final String name = e.getName();
			if(".".equals(name) || "..".equals(name))
				continue;
			if(Constants.DOT_GIT.equals(name))
				continue;
			if(Constants.DOT_GIT_IGNORE.equals(name))
				ignoreNode = new PerDirectoryIgnoreNode(
						TreeWalk.pathOf(path, 0, pathOffset)
								+ Constants.DOT_GIT_IGNORE,
						e);
			if(Constants.DOT_GIT_ATTRIBUTES.equals(name))
				attributesNode = new PerDirectoryAttributesNode(e);
			if(i != o)
				entries[o] = e;
			e.encodeName(nameEncoder);
			o++;
		}
		entryCnt = o;
		Arrays.sort(entries, 0, entryCnt, ENTRY_CMP);

		contentIdFromPtr = -1;
		ptr = 0;
		if(!eof())
			parseEntry();
		else if(pathLen == 0)
			pathLen = pathOffset;
	}

	protected Entry current() {
		return entries[ptr];
	}

	public enum MetadataDiff {
		EQUAL,
		DIFFER_BY_METADATA,
		SMUDGED,
		DIFFER_BY_TIMESTAMP
	}

	public boolean isModeDifferent(int rawMode) {
		int modeDiff = getEntryRawMode() ^ rawMode;

		if(modeDiff == 0)
			return false;

		if(getOptions().getSymLinks() == SymLinks.FALSE)
			if(FileMode.SYMLINK.equals(rawMode))
				return false;

		if(!state.options.isFileMode())
			modeDiff &= ~FileMode.EXECUTABLE_FILE.getBits();
		return modeDiff != 0;
	}

	public MetadataDiff compareMetadata(DirCacheEntry entry) {
		if(entry.isAssumeValid())
			return MetadataDiff.EQUAL;

		if(entry.isUpdateNeeded())
			return MetadataDiff.DIFFER_BY_METADATA;

		if(isModeDifferent(entry.getRawMode()))
			return MetadataDiff.DIFFER_BY_METADATA;

		int type = mode & FileMode.TYPE_MASK;
		if(type == FileMode.TYPE_TREE || type == FileMode.TYPE_GITLINK)
			return MetadataDiff.EQUAL;

		if(!entry.isSmudged() && entry.getLength() != (int) getEntryLength())
			return MetadataDiff.DIFFER_BY_METADATA;

		Instant cacheLastModified = entry.getLastModifiedInstant();
		Instant fileLastModified = getEntryLastModifiedInstant();
		if(timestampComparator.compare(cacheLastModified, fileLastModified,
				getOptions().getCheckStat() == CheckStat.MINIMAL) != 0) {
			return MetadataDiff.DIFFER_BY_TIMESTAMP;
		}

		if(entry.isSmudged()) {
			return MetadataDiff.SMUDGED;
		}
		return MetadataDiff.EQUAL;
	}

	public boolean isModified(DirCacheEntry entry, boolean forceContentCheck,
							  ObjectReader reader) throws IOException {
		if(entry == null)
			return !FileMode.MISSING.equals(getEntryFileMode());
		MetadataDiff diff = compareMetadata(entry);
		switch(diff) {
			case DIFFER_BY_TIMESTAMP:
				if(forceContentCheck) {
					return contentCheck(entry, reader);
				}
				return true;
			case SMUDGED:
				return contentCheck(entry, reader);
			case EQUAL:
				if(mode == FileMode.SYMLINK.getBits()) {
					return contentCheck(entry, reader);
				}
				return false;
			case DIFFER_BY_METADATA:
				if(mode == FileMode.TREE.getBits()
						&& entry.getFileMode().equals(FileMode.GITLINK)) {
					byte[] idBuffer = idBuffer();
					int idOffset = idOffset();
					if(entry.getObjectId().compareTo(idBuffer, idOffset) == 0) {
						return true;
					} else if(ObjectId.zeroId().compareTo(idBuffer,
							idOffset) == 0) {
						Path p = repository.getWorkTree().toPath()
								.resolve(entry.getPathString());
						return FileUtils.hasFiles(p);
					}
					return false;
				} else if(mode == FileMode.SYMLINK.getBits())
					return contentCheck(entry, reader);
				return true;
			default:
				throw new IllegalStateException(MessageFormat.format(
						JGitText.get().unexpectedCompareResult, diff.name()));
		}
	}

	public FileMode getIndexFileMode(DirCacheIterator indexIter) {
		final FileMode wtMode = getEntryFileMode();
		if(indexIter == null) {
			return wtMode;
		}
		final FileMode iMode = indexIter.getEntryFileMode();
		if(getOptions().isFileMode() && iMode != FileMode.GITLINK && iMode != FileMode.TREE) {
			return wtMode;
		}
		if(!getOptions().isFileMode()) {
			if(FileMode.REGULAR_FILE == wtMode
					&& FileMode.EXECUTABLE_FILE == iMode) {
				return iMode;
			}
			if(FileMode.EXECUTABLE_FILE == wtMode
					&& FileMode.REGULAR_FILE == iMode) {
				return iMode;
			}
		}
		if(FileMode.GITLINK == iMode
				&& FileMode.TREE == wtMode && !getOptions().isDirNoGitLinks()) {
			return iMode;
		}
		if(FileMode.TREE == iMode
				&& FileMode.GITLINK == wtMode) {
			return iMode;
		}
		return wtMode;
	}

	private boolean contentCheck(DirCacheEntry entry, ObjectReader reader)
			throws IOException {
		if(getEntryObjectId().equals(entry.getObjectId())) {
			entry.setLength((int) getEntryLength());

			return false;
		}
		if(mode == FileMode.SYMLINK.getBits()) {
			return !new File(readSymlinkTarget(current())).equals(
					new File(readContentAsNormalizedString(entry, reader)));
		}
		return true;
	}

	private static String readContentAsNormalizedString(DirCacheEntry entry,
														ObjectReader reader) throws IOException {
		ObjectLoader open = reader.open(entry.getObjectId());
		byte[] cachedBytes = open.getCachedBytes();
		return FS.detect().normalize(RawParseUtils.decode(cachedBytes));
	}

	protected String readSymlinkTarget(Entry entry) throws IOException {
		if(!entry.getMode().equals(FileMode.SYMLINK)) {
			throw new java.nio.file.NotLinkException(entry.getName());
		}
		long length = entry.getLength();
		byte[] content = new byte[(int) length];
		try(InputStream is = entry.openInputStream()) {
			int bytesRead = IO.readFully(is, content, 0);
			return FS.detect()
					.normalize(RawParseUtils.decode(content, 0, bytesRead));
		}
	}

	private static long computeLength(InputStream in) throws IOException {
		long length = 0;
		for(; ; ) {
			long n = in.skip(1 << 20);
			if(n <= 0)
				break;
			length += n;
		}
		return length;
	}

	private byte[] computeHash(InputStream in, long length) throws IOException {
		SHA1 contentDigest = SHA1.newInstance();
		final byte[] contentReadBuffer = state.contentReadBuffer;

		contentDigest.update(hblob);
		contentDigest.update((byte) ' ');

		long sz = length;
		if(sz == 0) {
			contentDigest.update((byte) '0');
		} else {
			final int bufn = contentReadBuffer.length;
			int p = bufn;
			do {
				contentReadBuffer[--p] = digits[(int) (sz % 10)];
				sz /= 10;
			} while(sz > 0);
			contentDigest.update(contentReadBuffer, p, bufn - p);
		}
		contentDigest.update((byte) 0);

		for(; ; ) {
			final int r = in.read(contentReadBuffer);
			if(r <= 0)
				break;
			contentDigest.update(contentReadBuffer, 0, r);
			sz += r;
		}
		if(sz != length)
			return zeroid;
		return contentDigest.digest();
	}

	public abstract static class Entry {
		byte[] encodedName;

		int encodedNameLen;

		void encodeName(CharsetEncoder enc) {
			final ByteBuffer b;
			try {
				b = enc.encode(CharBuffer.wrap(getName()));
			} catch(CharacterCodingException e) {
				throw new RuntimeException(MessageFormat.format(
						JGitText.get().unencodeableFile, getName()), e);
			}

			encodedNameLen = b.limit();
			if(b.hasArray() && b.arrayOffset() == 0)
				encodedName = b.array();
			else
				b.get(encodedName = new byte[encodedNameLen]);
		}

		@Override
		public String toString() {
			return getMode().toString() + " " + getName();
		}

		public abstract FileMode getMode();

		public abstract long getLength();

		public abstract Instant getLastModifiedInstant();

		public abstract String getName();

		public abstract InputStream openInputStream() throws IOException;
	}

	private static class PerDirectoryIgnoreNode extends IgnoreNode {
		protected final Entry entry;

		private final String name;

		PerDirectoryIgnoreNode(String name, Entry entry) {
			super(Collections.emptyList());
			this.name = name;
			this.entry = entry;
		}

		IgnoreNode load() throws IOException {
			IgnoreNode r = new IgnoreNode();
			try(InputStream in = entry.openInputStream()) {
				r.parse(name, in);
			}
			return r.getRules().isEmpty() ? null : r;
		}
	}

	private static class RootIgnoreNode extends PerDirectoryIgnoreNode {
		final Repository repository;

		RootIgnoreNode(Entry entry, Repository repository) {
			super(entry != null ? entry.getName() : null, entry);
			this.repository = repository;
		}

		@Override
		IgnoreNode load() throws IOException {
			IgnoreNode r;
			if(entry != null) {
				r = super.load();
				if(r == null)
					r = new IgnoreNode();
			} else {
				r = new IgnoreNode();
			}

			FS fs = repository.getFS();
			Path path = repository.getConfig().getPath(
					ConfigConstants.CONFIG_CORE_SECTION, null,
					ConfigConstants.CONFIG_KEY_EXCLUDESFILE, fs, null, null);
			if(path != null) {
				loadRulesFromFile(r, path.toFile());
			}

			File exclude = fs.resolve(repository.getDirectory(),
					Constants.INFO_EXCLUDE);
			loadRulesFromFile(r, exclude);

			return r.getRules().isEmpty() ? null : r;
		}

		private static void loadRulesFromFile(IgnoreNode r, File exclude)
				throws IOException {
			if(FS.DETECTED.exists(exclude)) {
				try(FileInputStream in = new FileInputStream(exclude)) {
					r.parse(exclude.getAbsolutePath(), in);
				}
			}
		}
	}

	private static class PerDirectoryAttributesNode extends AttributesNode {
		final Entry entry;

		PerDirectoryAttributesNode(Entry entry) {
			super(Collections.emptyList());
			this.entry = entry;
		}

		AttributesNode load() throws IOException {
			AttributesNode r = new AttributesNode();
			try(InputStream in = entry.openInputStream()) {
				r.parse(in);
			}
			return r.getRules().isEmpty() ? null : r;
		}
	}


	private static final class IteratorState {
		final WorkingTreeOptions options;
		final CharsetEncoder nameEncoder;
		byte[] contentReadBuffer;
		TreeWalk walk;
		int dirCacheTree = -1;
		boolean walkIgnored = false;

		final Map<String, Boolean> directoryToIgnored = new HashMap<>();

		IteratorState(WorkingTreeOptions options) {
			this.options = options;
			this.nameEncoder = UTF_8.newEncoder();
		}

		void initializeReadBuffer() {
			if(contentReadBuffer == null) {
				contentReadBuffer = new byte[BUFFER_SIZE];
			}
		}
	}

	public String getCleanFilterCommand() {
		if(cleanFilterCommandHolder == null) {
			String cmd = null;
			if(state.walk != null) {
				cmd = state.walk
						.getFilterCommand(Constants.ATTR_FILTER_TYPE_CLEAN);
			}
			cleanFilterCommandHolder = new Holder<>(cmd);
		}
		return cleanFilterCommandHolder.get();
	}

	private EolStreamType getEolStreamType() {
		if(eolStreamTypeHolder == null) {
			EolStreamType type = null;
			if(state.walk != null) {
				type = state.walk.getEolStreamType(OperationType.CHECKIN_OP);
				if(EolStreamType.AUTO_LF.equals(type) && hasCrLfInIndex(getDirCacheIterator())) {
					type = EolStreamType.DIRECT;
				}
			} else {
				switch(getOptions().getAutoCRLF()) {
					case FALSE:
						type = EolStreamType.DIRECT;
						break;
					case TRUE:
					case INPUT:
						type = EolStreamType.AUTO_LF;
						break;
				}
			}
			eolStreamTypeHolder = new Holder<>(type);
		}
		return eolStreamTypeHolder.get();
	}

	private boolean hasCrLfInIndex(DirCacheIterator dirCache) {
		if(dirCache == null) {
			return false;
		}
		DirCacheEntry entry = dirCache.getDirCacheEntry();
		if((entry.getRawMode() & FileMode.TYPE_MASK) == FileMode.TYPE_FILE) {
			ObjectId blobId = entry.getObjectId();
			if(entry.getStage() > 0
					&& entry.getStage() != DirCacheEntry.STAGE_2) {
				blobId = null;
				byte[] name = entry.getRawPath();
				int i = 0;
				while(!dirCache.eof()) {
					dirCache.next(1);
					i++;
					entry = dirCache.getDirCacheEntry();
					if(entry == null
							|| !Arrays.equals(name, entry.getRawPath())) {
						break;
					}
					if(entry.getStage() == DirCacheEntry.STAGE_2) {
						if((entry.getRawMode()
								& FileMode.TYPE_MASK) == FileMode.TYPE_FILE) {
							blobId = entry.getObjectId();
						}
						break;
					}
				}
				dirCache.back(i);
			}
			if(blobId != null) {
				try(ObjectReader reader = repository.newObjectReader()) {
					ObjectLoader loader = reader.open(blobId,
							Constants.OBJ_BLOB);
					try {
						byte[] raw = loader.getCachedBytes();
						return RawText.isCrLfText(raw, raw.length, true);
					} catch(LargeObjectException e) {
						try(InputStream in = loader.openStream()) {
							return RawText.isCrLfText(in);
						}
					}
				} catch(IOException ignored) {
				}
			}
		}
		return false;
	}

	private boolean isDirectoryIgnored(String pathRel) throws IOException {
		final int pOff = 0 < pathOffset ? pathOffset - 1 : pathOffset;
		final String base = TreeWalk.pathOf(this.path, 0, pOff);
		final String pathAbs = concatPath(base, pathRel);
		return isDirectoryIgnored(pathRel, pathAbs);
	}

	private boolean isDirectoryIgnored(String pathRel, String pathAbs)
			throws IOException {
		assert pathRel.length() == 0 || (pathRel.charAt(0) != '/'
				&& pathRel.charAt(pathRel.length() - 1) != '/');
		assert pathAbs.length() == 0 || (pathAbs.charAt(0) != '/'
				&& pathAbs.charAt(pathAbs.length() - 1) != '/');
		assert pathAbs.endsWith(pathRel);

		Boolean ignored = state.directoryToIgnored.get(pathAbs);
		if(ignored != null) {
			return ignored;
		}

		final String parentRel = getParentPath(pathRel);
		if(parentRel != null && isDirectoryIgnored(parentRel)) {
			state.directoryToIgnored.put(pathAbs, Boolean.TRUE);
			return true;
		}

		final IgnoreNode node = getIgnoreNode();
		for(String p = pathRel; node != null
				&& !"".equals(p); p = getParentPath(Objects.requireNonNull(p))) {
			ignored = node.checkIgnored(p, true);
			if(ignored != null) {
				state.directoryToIgnored.put(pathAbs, ignored);
				return ignored;
			}
		}

		if(!(this.parent instanceof WorkingTreeIterator)) {
			state.directoryToIgnored.put(pathAbs, Boolean.FALSE);
			return false;
		}

		final WorkingTreeIterator wtParent = (WorkingTreeIterator) this.parent;
		final String parentRelPath = concatPath(
				TreeWalk.pathOf(this.path, wtParent.pathOffset, pathOffset - 1),
				pathRel);
		assert concatPath(TreeWalk.pathOf(wtParent.path, 0,
				Math.max(0, wtParent.pathOffset - 1)), parentRelPath)
				.equals(pathAbs);
		return wtParent.isDirectoryIgnored(parentRelPath, pathAbs);
	}

	private static String getParentPath(String path) {
		final int slashIndex = path.lastIndexOf('/', path.length() - 2);
		if(slashIndex > 0) {
			return path.substring(path.charAt(0) == '/' ? 1 : 0, slashIndex);
		}
		return path.length() > 0 ? "" : null;
	}

	private static String concatPath(String p1, String p2) {
		return p1 + (p1.length() > 0 && p2.length() > 0 ? "/" : "") + p2;
	}
}
