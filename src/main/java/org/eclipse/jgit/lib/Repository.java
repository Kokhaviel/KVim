/*
 * Copyright (C) 2007, Dave Watson <dwatson@mimvista.com>
 * Copyright (C) 2008-2010, Google Inc.
 * Copyright (C) 2006-2010, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2006-2012, Shawn O. Pearce <spearce@spearce.org>
 * Copyright (C) 2012, Daniel Megert <daniel_megert@ch.ibm.com>
 * Copyright (C) 2017, Wim Jongman <wim.jongman@remainsoftware.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.lib;

import org.eclipse.jgit.annotations.*;
import org.eclipse.jgit.attributes.AttributesNodeProvider;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.errors.*;
import org.eclipse.jgit.events.*;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.revwalk.*;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.jgit.lib.Constants.LOCK_SUFFIX;

public abstract class Repository implements AutoCloseable {
	private static final Logger LOG = LoggerFactory.getLogger(Repository.class);
	private static final ListenerList globalListeners = new ListenerList();

	public static ListenerList getGlobalListenerList() {
		return globalListeners;
	}

	final AtomicInteger useCnt = new AtomicInteger(1);
	final AtomicLong closedAt = new AtomicLong();
	private final File gitDir;
	private final FS fs;
	private final ListenerList myListeners = new ListenerList();
	private final File workTree;
	private final File indexFile;
	private final String initialBranch;

	protected Repository(BaseRepositoryBuilder<?, ?> options) {
		gitDir = options.getGitDir();
		fs = options.getFS();
		workTree = options.getWorkTree();
		indexFile = options.getIndexFile();
		initialBranch = options.getInitialBranch();
	}

	public void fireEvent(RepositoryEvent<?> event) {
		event.setRepository(this);
		myListeners.dispatch(event);
		globalListeners.dispatch(event);
	}

	public void create() throws IOException {
		create(false);
	}

	public abstract void create(boolean bare) throws IOException;

	public File getDirectory() {
		return gitDir;
	}

	public abstract String getIdentifier();

	@NonNull
	public abstract ObjectDatabase getObjectDatabase();

	@NonNull
	public ObjectInserter newObjectInserter() {
		return getObjectDatabase().newInserter();
	}

	@NonNull
	public ObjectReader newObjectReader() {
		return getObjectDatabase().newReader();
	}

	@NonNull
	public abstract RefDatabase getRefDatabase();

	@NonNull
	public abstract StoredConfig getConfig();

	@NonNull
	public abstract AttributesNodeProvider createAttributesNodeProvider();

	public FS getFS() {
		return fs;
	}

	@Deprecated
	public boolean hasObject(AnyObjectId objectId) {
		try {
			return getObjectDatabase().has(objectId);
		} catch(IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@NonNull
	public ObjectLoader open(AnyObjectId objectId)
			throws IOException {
		return getObjectDatabase().open(objectId);
	}

	@NonNull
	public ObjectLoader open(AnyObjectId objectId, int typeHint)
			throws IOException {
		return getObjectDatabase().open(objectId, typeHint);
	}

	@NonNull
	public RefUpdate updateRef(String ref) throws IOException {
		return updateRef(ref, false);
	}

	@NonNull
	public RefUpdate updateRef(String ref, boolean detach) throws IOException {
		return getRefDatabase().newUpdate(ref, detach);
	}

	@Nullable
	public ObjectId resolve(String revstr)
			throws RevisionSyntaxException, IOException {
		try(RevWalk rw = new RevWalk(this)) {
			rw.setRetainBody(false);
			Object resolved = resolve(rw, revstr);
			if(resolved instanceof String) {
				final Ref ref = findRef((String) resolved);
				return ref != null ? ref.getLeaf().getObjectId() : null;
			}
			return (ObjectId) resolved;
		}
	}

	@Nullable
	private Object resolve(RevWalk rw, String revstr)
			throws IOException {
		char[] revChars = revstr.toCharArray();
		RevObject rev = null;
		String name = null;
		int done = 0;
		for(int i = 0; i < revChars.length; ++i) {
			switch(revChars[i]) {
				case '^':
					if(rev == null) {
						if(name == null)
							if(done == 0)
								name = new String(revChars, done, i);
							else {
								done = i + 1;
								break;
							}
						rev = parseSimple(rw, name);
						name = null;
						if(rev == null)
							return null;
					}
					if(i + 1 < revChars.length) {
						switch(revChars[i + 1]) {
							case '0':
							case '1':
							case '2':
							case '3':
							case '4':
							case '5':
							case '6':
							case '7':
							case '8':
							case '9':
								int j;
								rev = rw.parseCommit(rev);
								for(j = i + 1; j < revChars.length; ++j) {
									if(!Character.isDigit(revChars[j]))
										break;
								}
								String parentnum = new String(revChars, i + 1, j - i
										- 1);
								int pnum;
								try {
									pnum = Integer.parseInt(parentnum);
								} catch(NumberFormatException e) {
									RevisionSyntaxException rse = new RevisionSyntaxException(
											JGitText.get().invalidCommitParentNumber,
											revstr);
									rse.initCause(e);
									throw rse;
								}
								if(pnum != 0) {
									RevCommit commit = (RevCommit) rev;
									if(pnum > commit.getParentCount())
										rev = null;
									else
										rev = commit.getParent(pnum - 1);
								}
								i = j - 1;
								break;
							case '{':
								int k;
								String item = null;
								for(k = i + 2; k < revChars.length; ++k) {
									if(revChars[k] == '}') {
										item = new String(revChars, i + 2, k - i - 2);
										break;
									}
								}
								i = k;
								if(item != null)
									if(item.equals("tree")) {
										rev = rw.parseTree(rev);
									} else if(item.equals("commit")) {
										rev = rw.parseCommit(rev);
									} else if(item.equals("blob")) {
										rev = rw.peel(rev);
										if(!(rev instanceof RevBlob))
											throw new IncorrectObjectTypeException(rev,
													Constants.TYPE_BLOB);
									} else if(item.isEmpty()) {
										rev = rw.peel(rev);
									} else
										throw new RevisionSyntaxException(revstr);
								else
									throw new RevisionSyntaxException(revstr);
								break;
							default:
								rev = rw.peel(rev);
								if(rev instanceof RevCommit) {
									RevCommit commit = ((RevCommit) rev);
									if(commit.getParentCount() == 0)
										rev = null;
									else
										rev = commit.getParent(0);
								} else
									throw new IncorrectObjectTypeException(rev,
											Constants.TYPE_COMMIT);
						}
					} else {
						rev = rw.peel(rev);
						if(rev instanceof RevCommit) {
							RevCommit commit = ((RevCommit) rev);
							if(commit.getParentCount() == 0)
								rev = null;
							else
								rev = commit.getParent(0);
						} else
							throw new IncorrectObjectTypeException(rev,
									Constants.TYPE_COMMIT);
					}
					done = i + 1;
					break;
				case '~':
					if(rev == null) {
						if(name == null)
							if(done == 0)
								name = new String(revChars, done, i);
							else {
								done = i + 1;
								break;
							}
						rev = parseSimple(rw, name);
						name = null;
						if(rev == null)
							return null;
					}
					rev = rw.peel(rev);
					if(!(rev instanceof RevCommit))
						throw new IncorrectObjectTypeException(rev,
								Constants.TYPE_COMMIT);
					int l;
					for(l = i + 1; l < revChars.length; ++l) {
						if(!Character.isDigit(revChars[l]))
							break;
					}
					int dist;
					if(l - i > 1) {
						String distnum = new String(revChars, i + 1, l - i - 1);
						try {
							dist = Integer.parseInt(distnum);
						} catch(NumberFormatException e) {
							RevisionSyntaxException rse = new RevisionSyntaxException(
									JGitText.get().invalidAncestryLength, revstr);
							rse.initCause(e);
							throw rse;
						}
					} else
						dist = 1;
					while(dist > 0) {
						RevCommit commit = (RevCommit) rev;
						if(commit.getParentCount() == 0) {
							rev = null;
							break;
						}
						commit = commit.getParent(0);
						rw.parseHeaders(commit);
						rev = commit;
						--dist;
					}
					i = l - 1;
					done = l;
					break;
				case '@':
					if(rev != null)
						throw new RevisionSyntaxException(revstr);
					if(i + 1 == revChars.length)
						continue;
					if(i + 1 < revChars.length && revChars[i + 1] != '{')
						continue;
					int m;
					String time = null;
					for(m = i + 2; m < revChars.length; ++m) {
						if(revChars[m] == '}') {
							time = new String(revChars, i + 2, m - i - 2);
							break;
						}
					}
					if(time != null) {
						if(time.equals("upstream")) {
							if(name == null)
								name = new String(revChars, done, i);
							if(name.isEmpty())
								name = Constants.HEAD;
							if(!Repository.isValidRefName("x/" + name))
								throw new RevisionSyntaxException(MessageFormat
										.format(JGitText.get().invalidRefName,
												name),
										revstr);
							Ref ref = findRef(name);
							if(ref == null)
								return null;
							if(ref.isSymbolic())
								ref = ref.getLeaf();
							name = ref.getName();

							RemoteConfig remoteConfig;
							try {
								remoteConfig = new RemoteConfig(getConfig(),
										"origin");
							} catch(URISyntaxException e) {
								RevisionSyntaxException rse = new RevisionSyntaxException(
										revstr);
								rse.initCause(e);
								throw rse;
							}
							String remoteBranchName = getConfig()
									.getString(
											ConfigConstants.CONFIG_BRANCH_SECTION,
											Repository.shortenRefName(ref.getName()),
											ConfigConstants.CONFIG_KEY_MERGE);
							List<RefSpec> fetchRefSpecs = remoteConfig
									.getFetchRefSpecs();
							for(RefSpec refSpec : fetchRefSpecs) {
								if(refSpec.matchSource(remoteBranchName)) {
									RefSpec expandFromSource = refSpec
											.expandFromSource(remoteBranchName);
									name = expandFromSource.getDestination();
									break;
								}
							}
							if(name == null)
								throw new RevisionSyntaxException(revstr);
						} else if(time.matches("^-\\d+$")) {
							if(name != null) {
								throw new RevisionSyntaxException(revstr);
							}
							String previousCheckout = resolveReflogCheckout(
									-Integer.parseInt(time));
							if(ObjectId.isId(previousCheckout)) {
								rev = parseSimple(rw, previousCheckout);
							} else {
								name = previousCheckout;
							}
						} else {
							if(name == null)
								name = new String(revChars, done, i);
							if(name.isEmpty())
								name = Constants.HEAD;
							if(!Repository.isValidRefName("x/" + name))
								throw new RevisionSyntaxException(MessageFormat
										.format(JGitText.get().invalidRefName,
												name),
										revstr);
							Ref ref = findRef(name);
							name = null;
							if(ref == null)
								return null;
							if(ref.isSymbolic())
								ref = ref.getLeaf();
							rev = resolveReflog(rw, ref, time);
						}
						i = m;
					} else
						throw new RevisionSyntaxException(revstr);
					break;
				case ':': {
					RevTree tree;
					if(rev == null) {
						if(name == null)
							name = new String(revChars, done, i);
						if(name.isEmpty())
							name = Constants.HEAD;
						rev = parseSimple(rw, name);
					}
					if(rev == null)
						return null;
					tree = rw.parseTree(rev);
					if(i == revChars.length - 1)
						return tree.copy();

					TreeWalk tw = TreeWalk.forPath(rw.getObjectReader(),
							new String(revChars, i + 1, revChars.length - i - 1),
							tree);
					return tw != null ? tw.getObjectId(0) : null;
				}
				default:
					if(rev != null)
						throw new RevisionSyntaxException(revstr);
			}
		}
		if(rev != null)
			return rev.copy();
		if(name != null)
			return name;
		if(done == revstr.length())
			return null;
		name = revstr.substring(done);
		if(!Repository.isValidRefName("x/" + name))
			throw new RevisionSyntaxException(
					MessageFormat.format(JGitText.get().invalidRefName, name),
					revstr);
		if(findRef(name) != null)
			return name;
		return resolveSimple(name);
	}

	private static boolean isHex(char c) {
		return ('0' <= c && c <= '9')
				|| ('a' <= c && c <= 'f')
				|| ('A' <= c && c <= 'F');
	}

	private static boolean isAllHex(String str, int ptr) {
		while(ptr < str.length()) {
			if(!isHex(str.charAt(ptr++)))
				return false;
		}
		return true;
	}

	@Nullable
	private RevObject parseSimple(RevWalk rw, String revstr) throws IOException {
		ObjectId id = resolveSimple(revstr);
		return id != null ? rw.parseAny(id) : null;
	}

	@Nullable
	private ObjectId resolveSimple(String revstr) throws IOException {
		if(ObjectId.isId(revstr))
			return ObjectId.fromString(revstr);

		if(Repository.isValidRefName("x/" + revstr)) {
			Ref r = getRefDatabase().findRef(revstr);
			if(r != null)
				return r.getObjectId();
		}

		if(AbbreviatedObjectId.isId(revstr))
			return resolveAbbreviation(revstr);

		int dashg = revstr.indexOf("-g");
		if((dashg + 5) < revstr.length() && 0 <= dashg
				&& isHex(revstr.charAt(dashg + 2))
				&& isHex(revstr.charAt(dashg + 3))
				&& isAllHex(revstr, dashg + 4)) {
			String s = revstr.substring(dashg + 2);
			if(AbbreviatedObjectId.isId(s))
				return resolveAbbreviation(s);
		}

		return null;
	}

	@Nullable
	private String resolveReflogCheckout(int checkoutNo)
			throws IOException {
		ReflogReader reader = getReflogReader(Constants.HEAD);
		if(reader == null) {
			return null;
		}
		List<ReflogEntry> reflogEntries = reader.getReverseEntries();
		for(ReflogEntry entry : reflogEntries) {
			CheckoutEntry checkout = entry.parseCheckout();
			if(checkout != null)
				if(checkoutNo-- == 1)
					return checkout.getFromBranch();
		}
		return null;
	}

	private RevCommit resolveReflog(RevWalk rw, Ref ref, String time)
			throws IOException {
		int number;
		try {
			number = Integer.parseInt(time);
		} catch(NumberFormatException nfe) {
			RevisionSyntaxException rse = new RevisionSyntaxException(
					MessageFormat.format(JGitText.get().invalidReflogRevision,
							time));
			rse.initCause(nfe);
			throw rse;
		}
		assert number >= 0;
		ReflogReader reader = getReflogReader(ref.getName());
		if(reader == null) {
			throw new RevisionSyntaxException(
					MessageFormat.format(JGitText.get().reflogEntryNotFound,
							number, ref.getName()));
		}
		ReflogEntry entry = reader.getReverseEntry(number);
		if(entry == null)
			throw new RevisionSyntaxException(MessageFormat.format(
					JGitText.get().reflogEntryNotFound,
					number, ref.getName()));

		return rw.parseCommit(entry.getNewId());
	}

	@Nullable
	private ObjectId resolveAbbreviation(String revstr) throws IOException {
		AbbreviatedObjectId id = AbbreviatedObjectId.fromString(revstr);
		try(ObjectReader reader = newObjectReader()) {
			Collection<ObjectId> matches = reader.resolve(id);
			if(matches.isEmpty())
				return null;
			else if(matches.size() == 1)
				return matches.iterator().next();
			else
				throw new AmbiguousObjectException(id);
		}
	}

	public void incrementOpen() {
		useCnt.incrementAndGet();
	}

	@Override
	public void close() {
		int newCount = useCnt.decrementAndGet();
		if(newCount == 0) {
			if(RepositoryCache.isCached(this)) {
				closedAt.set(System.currentTimeMillis());
			} else {
				doClose();
			}
		} else if(newCount == -1) {
			String message = MessageFormat.format(JGitText.get().corruptUseCnt,
					toString());
			if(LOG.isDebugEnabled()) {
				LOG.debug(message, new IllegalStateException());
			} else {
				LOG.warn(message);
			}
			if(RepositoryCache.isCached(this)) {
				closedAt.set(System.currentTimeMillis());
			}
		}
	}

	protected void doClose() {
		getObjectDatabase().close();
		getRefDatabase().close();
	}

	@Override
	@NonNull
	public String toString() {
		String desc;
		File directory = getDirectory();
		if(directory != null)
			desc = directory.getPath();
		else
			desc = getClass().getSimpleName() + "-"
					+ System.identityHashCode(this);
		return "Repository[" + desc + "]";
	}

	@Nullable
	public String getFullBranch() throws IOException {
		Ref head = exactRef(Constants.HEAD);
		if(head == null) {
			return null;
		}
		if(head.isSymbolic()) {
			return head.getTarget().getName();
		}
		ObjectId objectId = head.getObjectId();
		if(objectId != null) {
			return objectId.name();
		}
		return null;
	}

	@Nullable
	public String getBranch() throws IOException {
		String name = getFullBranch();
		if(name != null)
			return shortenRefName(name);
		return null;
	}

	protected @NonNull String getInitialBranch() {
		return initialBranch;
	}

	@NonNull
	public Set<ObjectId> getAdditionalHaves() throws IOException {
		return Collections.emptySet();
	}

	@Nullable
	public final Ref exactRef(String name) throws IOException {
		return getRefDatabase().exactRef(name);
	}

	@Nullable
	public final Ref findRef(String name) throws IOException {
		return getRefDatabase().findRef(name);
	}

	@Deprecated
	@NonNull
	public Map<String, Ref> getAllRefs() {
		try {
			return getRefDatabase().getRefs(RefDatabase.ALL);
		} catch(IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Deprecated
	@NonNull
	public Map<String, Ref> getTags() {
		try {
			return getRefDatabase().getRefs(Constants.R_TAGS);
		} catch(IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Deprecated
	@NonNull
	public Ref peel(Ref ref) {
		try {
			return getRefDatabase().peel(ref);
		} catch(IOException e) {
			return ref;
		}
	}

	@NonNull
	public File getIndexFile() throws NoWorkTreeException {
		if(isBare())
			throw new NoWorkTreeException();
		return indexFile;
	}

	public RevCommit parseCommit(AnyObjectId id) throws IOException {
		if(id instanceof RevCommit && ((RevCommit) id).getRawBuffer() != null) {
			return (RevCommit) id;
		}
		try(RevWalk walk = new RevWalk(this)) {
			return walk.parseCommit(id);
		}
	}

	@NonNull
	public DirCache readDirCache() throws NoWorkTreeException, IOException {
		return DirCache.read(this);
	}

	@NonNull
	public DirCache lockDirCache() throws NoWorkTreeException, IOException {
		IndexChangedListener l = (IndexChangedEvent event) -> notifyIndexChanged(true);
		return DirCache.lock(this, l);
	}

	@NonNull
	public RepositoryState getRepositoryState() {
		if(isBare() || getDirectory() == null)
			return RepositoryState.BARE;

		if(new File(getWorkTree(), ".dotest").exists())
			return RepositoryState.REBASING;
		if(new File(getDirectory(), ".dotest-merge").exists())
			return RepositoryState.REBASING_INTERACTIVE;

		if(new File(getDirectory(), "rebase-apply/rebasing").exists())
			return RepositoryState.REBASING_REBASING;
		if(new File(getDirectory(), "rebase-apply/applying").exists())
			return RepositoryState.APPLY;
		if(new File(getDirectory(), "rebase-apply").exists())
			return RepositoryState.REBASING;

		if(new File(getDirectory(), "rebase-merge/interactive").exists())
			return RepositoryState.REBASING_INTERACTIVE;
		if(new File(getDirectory(), "rebase-merge").exists())
			return RepositoryState.REBASING_MERGE;

		if(new File(getDirectory(), Constants.MERGE_HEAD).exists()) {
			try {
				if(!readDirCache().hasUnmergedPaths()) {
					return RepositoryState.MERGING_RESOLVED;
				}
			} catch(IOException e) {
				throw new UncheckedIOException(e);
			}
			return RepositoryState.MERGING;
		}

		if(new File(getDirectory(), "BISECT_LOG").exists())
			return RepositoryState.BISECTING;

		if(new File(getDirectory(), Constants.CHERRY_PICK_HEAD).exists()) {
			try {
				if(!readDirCache().hasUnmergedPaths()) {
					return RepositoryState.CHERRY_PICKING_RESOLVED;
				}
			} catch(IOException e) {
				throw new UncheckedIOException(e);
			}

			return RepositoryState.CHERRY_PICKING;
		}

		if(new File(getDirectory(), Constants.REVERT_HEAD).exists()) {
			try {
				if(!readDirCache().hasUnmergedPaths()) {
					return RepositoryState.REVERTING_RESOLVED;
				}
			} catch(IOException e) {
				throw new UncheckedIOException(e);
			}

			return RepositoryState.REVERTING;
		}

		return RepositoryState.SAFE;
	}

	public static boolean isValidRefName(String refName) {
		final int len = refName.length();
		if(len == 0) {
			return false;
		}
		if(refName.endsWith(LOCK_SUFFIX)) {
			return false;
		}

		try {
			SystemReader.getInstance().checkPath(refName);
		} catch(CorruptObjectException e) {
			return false;
		}

		int components = 1;
		char p = '\0';
		for(int i = 0; i < len; i++) {
			final char c = refName.charAt(i);
			if(c <= ' ')
				return false;
			switch(c) {
				case '.':
					switch(p) {
						case '\0':
						case '/':
						case '.':
							return false;
					}
					if(i == len - 1)
						return false;
					break;
				case '/':
					if(i == 0 || i == len - 1)
						return false;
					if(p == '/')
						return false;
					components++;
					break;
				case '{':
					if(p == '@')
						return false;
					break;
				case '~':
				case '^':
				case ':':
				case '?':
				case '[':
				case '*':
				case '\\':
				case '\u007F':
					return false;
			}
			p = c;
		}
		return components > 1;
	}

	@NonNull
	public static String stripWorkDir(File workDir, File file) {
		final String filePath = file.getPath();
		final String workDirPath = workDir.getPath();

		if(filePath.length() <= workDirPath.length()
				|| filePath.charAt(workDirPath.length()) != File.separatorChar
				|| !filePath.startsWith(workDirPath)) {
			File absWd = workDir.isAbsolute() ? workDir
					: workDir.getAbsoluteFile();
			File absFile = file.isAbsolute() ? file : file.getAbsoluteFile();
			if(absWd.equals(workDir) && absFile.equals(file)) {
				return "";
			}
			return stripWorkDir(absWd, absFile);
		}

		String relName = filePath.substring(workDirPath.length() + 1);
		if(File.separatorChar != '/') {
			relName = relName.replace(File.separatorChar, '/');
		}
		return relName;
	}

	public boolean isBare() {
		return workTree == null;
	}

	@NonNull
	public File getWorkTree() throws NoWorkTreeException {
		if(isBare())
			throw new NoWorkTreeException();
		return workTree;
	}

	public abstract void notifyIndexChanged(boolean internal);

	@NonNull
	public static String shortenRefName(String refName) {
		if(refName.startsWith(Constants.R_HEADS))
			return refName.substring(Constants.R_HEADS.length());
		if(refName.startsWith(Constants.R_TAGS))
			return refName.substring(Constants.R_TAGS.length());
		if(refName.startsWith(Constants.R_REMOTES))
			return refName.substring(Constants.R_REMOTES.length());
		return refName;
	}

	@Nullable
	public String shortenRemoteBranchName(String refName) {
		for(String remote : getRemoteNames()) {
			String remotePrefix = Constants.R_REMOTES + remote + "/";
			if(refName.startsWith(remotePrefix))
				return refName.substring(remotePrefix.length());
		}
		return null;
	}

	@Nullable
	public String getRemoteName(String refName) {
		for(String remote : getRemoteNames()) {
			String remotePrefix = Constants.R_REMOTES + remote + "/";
			if(refName.startsWith(remotePrefix))
				return remote;
		}
		return null;
	}

	@Nullable
	public abstract ReflogReader getReflogReader(String refName)
			throws IOException;

	@Nullable
	public String readMergeCommitMsg() throws IOException, NoWorkTreeException {
		return readCommitMsgFile(Constants.MERGE_MSG);
	}

	public void writeMergeCommitMsg(String msg) throws IOException {
		File mergeMsgFile = new File(gitDir, Constants.MERGE_MSG);
		writeCommitMsg(mergeMsgFile, msg);
	}

	@Nullable
	public String readCommitEditMsg() throws IOException, NoWorkTreeException {
		return readCommitMsgFile(Constants.COMMIT_EDITMSG);
	}

	public void writeCommitEditMsg(String msg) throws IOException {
		File commiEditMsgFile = new File(gitDir, Constants.COMMIT_EDITMSG);
		writeCommitMsg(commiEditMsgFile, msg);
	}

	@Nullable
	public List<ObjectId> readMergeHeads() throws IOException, NoWorkTreeException {
		if(isBare() || getDirectory() == null)
			throw new NoWorkTreeException();

		byte[] raw = readGitDirectoryFile(Constants.MERGE_HEAD);
		if(raw == null)
			return null;

		LinkedList<ObjectId> heads = new LinkedList<>();
		for(int p = 0; p < raw.length; ) {
			heads.add(ObjectId.fromString(raw, p));
			p = RawParseUtils
					.nextLF(raw, p + Constants.OBJECT_ID_STRING_LENGTH);
		}
		return heads;
	}

	public void writeMergeHeads(List<? extends ObjectId> heads) throws IOException {
		writeHeadsFile(heads, Constants.MERGE_HEAD);
	}

	public void writeCherryPickHead(ObjectId head) throws IOException {
		List<ObjectId> heads = (head != null) ? Collections.singletonList(head)
				: null;
		writeHeadsFile(heads, Constants.CHERRY_PICK_HEAD);
	}

	public void writeRevertHead(ObjectId head) throws IOException {
		List<ObjectId> heads = (head != null) ? Collections.singletonList(head)
				: null;
		writeHeadsFile(heads, Constants.REVERT_HEAD);
	}

	public void writeOrigHead(ObjectId head) throws IOException {
		List<ObjectId> heads = head != null ? Collections.singletonList(head)
				: null;
		writeHeadsFile(heads, Constants.ORIG_HEAD);
	}

	@Nullable
	public ObjectId readOrigHead() throws IOException, NoWorkTreeException {
		if(isBare() || getDirectory() == null)
			throw new NoWorkTreeException();

		byte[] raw = readGitDirectoryFile(Constants.ORIG_HEAD);
		return raw != null ? ObjectId.fromString(raw, 0) : null;
	}

	@Nullable
	public String readSquashCommitMsg() throws IOException {
		return readCommitMsgFile(Constants.SQUASH_MSG);
	}

	public void writeSquashCommitMsg(String msg) throws IOException {
		File squashMsgFile = new File(gitDir, Constants.SQUASH_MSG);
		writeCommitMsg(squashMsgFile, msg);
	}

	@Nullable
	private String readCommitMsgFile(String msgFilename) throws IOException {
		if(isBare() || getDirectory() == null)
			throw new NoWorkTreeException();

		File mergeMsgFile = new File(getDirectory(), msgFilename);
		try {
			return RawParseUtils.decode(IO.readFully(mergeMsgFile));
		} catch(FileNotFoundException e) {
			if(mergeMsgFile.exists()) {
				throw e;
			}
			return null;
		}
	}

	private void writeCommitMsg(File msgFile, String msg) throws IOException {
		if(msg != null) {
			try(FileOutputStream fos = new FileOutputStream(msgFile)) {
				fos.write(msg.getBytes(UTF_8));
			}
		} else {
			FileUtils.delete(msgFile, FileUtils.SKIP_MISSING);
		}
	}

	private byte[] readGitDirectoryFile(String filename) throws IOException {
		File file = new File(getDirectory(), filename);
		try {
			byte[] raw = IO.readFully(file);
			return raw.length > 0 ? raw : null;
		} catch(FileNotFoundException notFound) {
			if(file.exists()) {
				throw notFound;
			}
			return null;
		}
	}

	private void writeHeadsFile(List<? extends ObjectId> heads, String filename) throws IOException {
		File headsFile = new File(getDirectory(), filename);
		if(heads != null) {
			try(OutputStream bos = new BufferedOutputStream(
					Files.newOutputStream(headsFile.toPath()))) {
				for(ObjectId id : heads) {
					id.copyTo(bos);
					bos.write('\n');
				}
			}
		} else {
			FileUtils.delete(headsFile, FileUtils.SKIP_MISSING);
		}
	}

	@NonNull
	public List<RebaseTodoLine> readRebaseTodo(String path,
											   boolean includeComments)
			throws IOException {
		return new RebaseTodoFile(this).readRebaseTodo(path, includeComments);
	}

	public void writeRebaseTodoFile(String path, List<RebaseTodoLine> steps,
									boolean append)
			throws IOException {
		new RebaseTodoFile(this).writeRebaseTodoFile(path, steps, append);
	}

	@NonNull
	public Set<String> getRemoteNames() {
		return getConfig()
				.getSubsections(ConfigConstants.CONFIG_REMOTE_SECTION);
	}

	public void autoGC(ProgressMonitor monitor) {
	}
}
