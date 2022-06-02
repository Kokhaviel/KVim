/*
 * Copyright (C) 2008, 2009 Google Inc.
 * Copyright (C) 2008, 2022 Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.treewalk;

import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.attributes.*;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.StopWalkException;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.lib.CoreConfig.EolStreamType;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.eclipse.jgit.util.QuotedString;
import org.eclipse.jgit.util.RawParseUtils;
import org.eclipse.jgit.util.io.EolStreamTypeUtil;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

import static java.nio.charset.StandardCharsets.UTF_8;

public class TreeWalk implements AutoCloseable, AttributesProvider {

	private static final AbstractTreeIterator[] NO_TREES = {};

	public enum OperationType {
		CHECKOUT_OP,
		CHECKIN_OP
	}

	private OperationType operationType = OperationType.CHECKOUT_OP;
	private final Map<String, String> filterCommandsByNameDotType = new HashMap<>();

	public void setOperationType(OperationType operationType) {
		this.operationType = operationType;
	}

	public static TreeWalk forPath(final ObjectReader reader, final String path,
								   final AnyObjectId... trees) throws
			IOException {
		return forPath(null, reader, path, trees);
	}

	public static TreeWalk forPath(final @Nullable Repository repo,
								   final ObjectReader reader, final String path,
								   final AnyObjectId... trees)
			throws IOException {
		TreeWalk tw = new TreeWalk(repo, reader);
		PathFilter f = PathFilter.create(path);
		tw.setFilter(f);
		tw.reset(trees);
		tw.setRecursive(false);

		while(tw.next()) {
			if(f.isDone(tw)) {
				return tw;
			} else if(tw.isSubtree()) {
				tw.enterSubtree();
			}
		}
		return null;
	}

	public static TreeWalk forPath(final Repository db, final String path,
								   final AnyObjectId... trees) throws IOException {
		try(ObjectReader reader = db.newObjectReader()) {
			return forPath(db, reader, path, trees);
		}
	}

	public static TreeWalk forPath(final Repository db, final String path,
								   final RevTree tree) throws IOException {
		return forPath(db, path, new ObjectId[] {tree});
	}

	private final ObjectReader reader;
	private final boolean closeReader;
	private final MutableObjectId idBuffer = new MutableObjectId();
	private TreeFilter filter;
	AbstractTreeIterator[] trees;
	private boolean recursive;
	private boolean postOrderTraversal;
	int depth;
	private boolean advance;
	private final AttributesNodeProvider attributesNodeProvider;
	AbstractTreeIterator currentHead;
	private Attributes[] attrs;
	private AttributesHandler[] attributesHandlers;
	private int headIndex = -1;
	private final Config config;
	private Set<String> filterCommands;

	public TreeWalk(Repository repo) {
		this(repo, repo.newObjectReader(), true);
	}

	public TreeWalk(@Nullable Repository repo, ObjectReader or) {
		this(repo, or, false);
	}

	public TreeWalk(ObjectReader or) {
		this(null, or, false);
	}

	private TreeWalk(final @Nullable Repository repo, final ObjectReader or,
					 final boolean closeReader) {
		if(repo != null) {
			config = repo.getConfig();
			attributesNodeProvider = repo.createAttributesNodeProvider();
			filterCommands = FilterCommandRegistry
					.getRegisteredFilterCommands();
		} else {
			config = null;
			attributesNodeProvider = null;
		}
		reader = or;
		filter = TreeFilter.ALL;
		trees = NO_TREES;
		this.closeReader = closeReader;
	}

	public ObjectReader getObjectReader() {
		return reader;
	}

	public OperationType getOperationType() {
		return operationType;
	}

	@Override
	public void close() {
		if(closeReader) {
			reader.close();
		}
	}

	public TreeFilter getFilter() {
		return filter;
	}

	public void setFilter(TreeFilter newFilter) {
		filter = newFilter != null ? newFilter : TreeFilter.ALL;
	}

	public boolean isRecursive() {
		return recursive;
	}

	public void setRecursive(boolean b) {
		recursive = b;
	}

	public boolean isPostOrderTraversal() {
		return postOrderTraversal;
	}

	public AttributesNodeProvider getAttributesNodeProvider() {
		return attributesNodeProvider;
	}

	public void setHead(int index) {
		if(index < 0 || index >= trees.length) {
			throw new IllegalArgumentException("Head index " + index
					+ " out of range [0," + trees.length + ')');
		}
		headIndex = index;
	}

	@Override
	public Attributes getAttributes() {
		return getAttributes(headIndex);
	}

	public Attributes getAttributes(int index) {
		int attrIndex = index + 1;
		Attributes result = attrs[attrIndex];
		if(result != null) {
			return result;
		}
		if(attributesNodeProvider == null) {
			throw new IllegalStateException(
					"The tree walk should have one AttributesNodeProvider set in order to compute the git attributes.");
		}

		try {
			AttributesHandler handler = attributesHandlers[attrIndex];
			if(handler == null) {
				if(index < 0) {
					handler = new AttributesHandler(this, () -> getTree(CanonicalTreeParser.class));
				} else {
					handler = new AttributesHandler(this, () -> {
						AbstractTreeIterator tree = trees[index];
						if(tree instanceof CanonicalTreeParser) {
							return (CanonicalTreeParser) tree;
						}
						return null;
					});
				}
				attributesHandlers[attrIndex] = handler;
			}
			result = handler.getAttributes();
			attrs[attrIndex] = result;
			return result;
		} catch(IOException e) {
			throw new JGitInternalException("Error while parsing attributes",
					e);
		}
	}

	@Nullable
	public EolStreamType getEolStreamType(OperationType opType) {
		if(attributesNodeProvider == null || config == null) {
			return null;
		}
		OperationType op = opType != null ? opType : operationType;
		return EolStreamTypeUtil.detectStreamType(op,
				config.get(WorkingTreeOptions.KEY), getAttributes());
	}

	@Nullable
	public EolStreamType getCheckoutEolStreamType(int tree) {
		if(attributesNodeProvider == null || config == null) {
			return null;
		}
		Attributes attr = getAttributes(tree);
		return EolStreamTypeUtil.detectStreamType(OperationType.CHECKOUT_OP,
				config.get(WorkingTreeOptions.KEY), attr);
	}

	public void reset() {
		attrs = null;
		attributesHandlers = null;
		headIndex = -1;
		trees = NO_TREES;
		advance = false;
		depth = 0;
	}

	public void reset(AnyObjectId id) throws IOException {
		if(trees.length == 1) {
			AbstractTreeIterator o = trees[0];
			while(o.parent != null)
				o = o.parent;
			if(o instanceof CanonicalTreeParser) {
				o.matches = null;
				o.matchShift = 0;
				((CanonicalTreeParser) o).reset(reader, id);
				trees[0] = o;
			} else {
				trees[0] = parserFor(id);
			}
		} else {
			trees = new AbstractTreeIterator[] {parserFor(id)};
		}

		advance = false;
		depth = 0;
		attrs = new Attributes[2];
		attributesHandlers = new AttributesHandler[2];
		headIndex = -1;
	}

	public void reset(AnyObjectId... ids) throws IOException {
		final int oldLen = trees.length;
		final int newLen = ids.length;
		final AbstractTreeIterator[] r = newLen == oldLen ? trees
				: new AbstractTreeIterator[newLen];
		for(int i = 0; i < newLen; i++) {
			AbstractTreeIterator o;

			if(i < oldLen) {
				o = trees[i];
				while(o.parent != null)
					o = o.parent;
				if(o instanceof CanonicalTreeParser && o.pathOffset == 0) {
					o.matches = null;
					o.matchShift = 0;
					((CanonicalTreeParser) o).reset(reader, ids[i]);
					r[i] = o;
					continue;
				}
			}

			o = parserFor(ids[i]);
			r[i] = o;
		}

		trees = r;
		advance = false;
		depth = 0;
		if(oldLen == newLen) {
			Arrays.fill(attrs, null);
			Arrays.fill(attributesHandlers, null);
		} else {
			attrs = new Attributes[newLen + 1];
			attributesHandlers = new AttributesHandler[newLen + 1];
		}
		headIndex = -1;
	}

	public int addTree(AnyObjectId id) throws IOException {
		return addTree(parserFor(id));
	}

	public int addTree(AbstractTreeIterator p) {
		int n = trees.length;
		AbstractTreeIterator[] newTrees = new AbstractTreeIterator[n + 1];

		System.arraycopy(trees, 0, newTrees, 0, n);
		newTrees[n] = p;
		p.matches = null;
		p.matchShift = 0;

		trees = newTrees;
		if(attrs == null) {
			attrs = new Attributes[n + 2];
		} else {
			attrs = Arrays.copyOf(attrs, n + 2);
		}
		if(attributesHandlers == null) {
			attributesHandlers = new AttributesHandler[n + 2];
		} else {
			attributesHandlers = Arrays.copyOf(attributesHandlers, n + 2);
		}
		return n;
	}

	public int getTreeCount() {
		return trees.length;
	}

	public boolean next() throws IOException {
		try {
			if(advance) {
				advance = false;
				popEntriesEqual();
			}

			for(; ; ) {
				Arrays.fill(attrs, null);
				final AbstractTreeIterator t = min();
				if(t.eof()) {
					if(depth > 0) {
						exitSubtree();
						if(postOrderTraversal) {
							advance = true;
							return true;
						}
						popEntriesEqual();
						continue;
					}
					return false;
				}

				currentHead = t;
				if(filter.matchFilter(this) == 1) {
					skipEntriesEqual();
					continue;
				}

				if(recursive && FileMode.TREE.equals(t.mode)) {
					enterSubtree();
					continue;
				}

				advance = true;
				return true;
			}
		} catch(StopWalkException stop) {
			stopWalk();
			return false;
		}
	}

	void stopWalk() throws IOException {
		for(AbstractTreeIterator t : trees) {
			t.stopWalk();
		}
	}

	@SuppressWarnings("unchecked")
	public <T extends AbstractTreeIterator> T getTree(final int nth) {
		final AbstractTreeIterator t = trees[nth];
		return t.matches == currentHead ? (T) t : null;
	}

	public int getRawMode(int nth) {
		final AbstractTreeIterator t = trees[nth];
		return t.matches == currentHead ? t.mode : 0;
	}

	public FileMode getFileMode(int nth) {
		return FileMode.fromBits(getRawMode(nth));
	}

	public FileMode getFileMode() {
		return FileMode.fromBits(currentHead.mode);
	}

	public ObjectId getObjectId(int nth) {
		final AbstractTreeIterator t = trees[nth];
		return t.matches == currentHead ? t.getEntryObjectId() : ObjectId
				.zeroId();
	}

	public void getObjectId(MutableObjectId out, int nth) {
		final AbstractTreeIterator t = trees[nth];
		if(t.matches == currentHead)
			t.getEntryObjectId(out);
		else
			out.clear();
	}

	public boolean idEqual(int nthA, int nthB) {
		final AbstractTreeIterator ch = currentHead;
		final AbstractTreeIterator a = trees[nthA];
		final AbstractTreeIterator b = trees[nthB];
		if(a.matches != ch && b.matches != ch) {
			return true;
		}
		if(!a.hasId() || !b.hasId())
			return false;
		if(a.matches == ch && b.matches == ch)
			return a.idEqual(b);
		return false;
	}

	public String getPathString() {
		return pathOf(currentHead);
	}

	public byte[] getRawPath() {
		final AbstractTreeIterator t = currentHead;
		final int n = t.pathLen;
		final byte[] r = new byte[n];
		System.arraycopy(t.path, 0, r, 0, n);
		return r;
	}

	public int getPathLength() {
		return currentHead.pathLen;
	}

	public int isPathMatch(byte[] p, int pLen) {
		final AbstractTreeIterator t = currentHead;
		final byte[] c = t.path;
		final int cLen = t.pathLen;
		int ci;

		for(ci = 0; ci < cLen && ci < pLen; ci++) {
			final int c_value = (c[ci] & 0xff) - (p[ci] & 0xff);
			if(c_value != 0) {
				return 1;
			}
		}

		if(ci < cLen) {
			return c[ci] == '/' ? 0 : 1;
		}

		if(ci < pLen) {
			return p[ci] == '/' && FileMode.TREE.equals(t.mode) ? -1 : 1;
		}

		return 0;
	}

	public int isPathPrefix(byte[] p, int pLen) {
		final AbstractTreeIterator t = currentHead;
		final byte[] c = t.path;
		final int cLen = t.pathLen;
		int ci;

		for(ci = 0; ci < cLen && ci < pLen; ci++) {
			final int c_value = (c[ci] & 0xff) - (p[ci] & 0xff);
			if(c_value != 0)
				return c_value;
		}

		if(ci < cLen) {
			return c[ci] == '/' ? 0 : -1;
		}

		if(ci < pLen) {
			return p[ci] == '/' && FileMode.TREE.equals(t.mode) ? 0 : -1;
		}

		return 0;
	}

	public int getDepth() {
		return depth;
	}

	public boolean isSubtree() {
		return FileMode.TREE.equals(currentHead.mode);
	}

	public void enterSubtree() throws IOException {
		Arrays.fill(attrs, null);
		final AbstractTreeIterator ch = currentHead;
		final AbstractTreeIterator[] tmp = new AbstractTreeIterator[trees.length];
		for(int i = 0; i < trees.length; i++) {
			final AbstractTreeIterator t = trees[i];
			final AbstractTreeIterator n;
			if(t.matches == ch && !t.eof() &&
					(FileMode.TREE.equals(t.mode)
							|| (FileMode.GITLINK.equals(t.mode) && t.isWorkTree())))
				n = t.createSubtreeIterator(reader, idBuffer);
			else
				n = t.createEmptyTreeIterator();
			tmp[i] = n;
		}
		depth++;
		advance = false;
		System.arraycopy(tmp, 0, trees, 0, trees.length);
	}

	AbstractTreeIterator min() throws CorruptObjectException {
		int i = 0;
		AbstractTreeIterator minRef = trees[i];
		while(minRef.eof() && ++i < trees.length)
			minRef = trees[i];
		if(minRef.eof())
			return minRef;

		minRef.matches = minRef;
		while(++i < trees.length) {
			final AbstractTreeIterator t = trees[i];
			if(t.eof())
				continue;
			final int cmp = t.pathCompare(minRef);
			if(cmp < 0) {
				t.matches = t;
				minRef = t;
			} else if(cmp == 0) {
				t.matches = minRef;
			}
		}

		return minRef;
	}

	void popEntriesEqual() throws CorruptObjectException {
		final AbstractTreeIterator ch = currentHead;
		for(AbstractTreeIterator t : trees) {
			if(t.matches == ch) {
				t.next(1);
				t.matches = null;
			}
		}
	}

	void skipEntriesEqual() throws CorruptObjectException {
		final AbstractTreeIterator ch = currentHead;
		for(AbstractTreeIterator t : trees) {
			if(t.matches == ch) {
				t.skip();
				t.matches = null;
			}
		}
	}

	void exitSubtree() {
		depth--;
		for(int i = 0; i < trees.length; i++)
			trees[i] = trees[i].parent;

		AbstractTreeIterator minRef = null;
		for(AbstractTreeIterator t : trees) {
			if(t.matches != t)
				continue;
			if(minRef == null || t.pathCompare(minRef) < 0)
				minRef = t;
		}
		currentHead = minRef;
	}

	private CanonicalTreeParser parserFor(AnyObjectId id)
			throws IOException {
		final CanonicalTreeParser p = new CanonicalTreeParser();
		p.reset(reader, id);
		return p;
	}

	static String pathOf(AbstractTreeIterator t) {
		return RawParseUtils.decode(UTF_8, t.path, 0, t.pathLen);
	}

	static String pathOf(byte[] buf, int pos, int end) {
		return RawParseUtils.decode(UTF_8, buf, pos, end);
	}

	public <T extends AbstractTreeIterator> T getTree(Class<T> type) {
		for(AbstractTreeIterator tree : trees) {
			if(type.isInstance(tree)) {
				return type.cast(tree);
			}
		}
		return null;
	}

	public String getFilterCommand(String filterCommandType) {
		Attributes attributes = getAttributes();

		Attribute f = attributes.get(Constants.ATTR_FILTER);
		if(f == null) {
			return null;
		}
		String filterValue = f.getValue();
		if(filterValue == null) {
			return null;
		}

		String filterCommand = getFilterCommandDefinition(filterValue,
				filterCommandType);
		if(filterCommand == null) {
			return null;
		}
		return filterCommand.replaceAll("%f",
				Matcher.quoteReplacement(
						QuotedString.BOURNE.quote((getPathString()))));
	}

	public String getSmudgeCommand(int index) {
		return getSmudgeCommand(getAttributes(index));
	}

	public String getSmudgeCommand(Attributes attributes) {
		if(attributes == null) {
			return null;
		}
		Attribute f = attributes.get(Constants.ATTR_FILTER);
		if(f == null) {
			return null;
		}
		String filterValue = f.getValue();
		if(filterValue == null) {
			return null;
		}

		String filterCommand = getFilterCommandDefinition(filterValue,
				Constants.ATTR_FILTER_TYPE_SMUDGE);
		if(filterCommand == null) {
			return null;
		}
		return filterCommand.replaceAll("%f",
				Matcher.quoteReplacement(
						QuotedString.BOURNE.quote((getPathString()))));
	}

	private String getFilterCommandDefinition(String filterDriverName,
											  String filterCommandType) {
		String key = filterDriverName + "." + filterCommandType;
		String filterCommand = filterCommandsByNameDotType.get(key);
		if(filterCommand != null)
			return filterCommand;
		filterCommand = config.getString(ConfigConstants.CONFIG_FILTER_SECTION,
				filterDriverName, filterCommandType);
		boolean useBuiltin = config.getBoolean(
				ConfigConstants.CONFIG_FILTER_SECTION,
				filterDriverName, ConfigConstants.CONFIG_KEY_USEJGITBUILTIN, false);
		if(useBuiltin) {
			String builtinFilterCommand = Constants.BUILTIN_FILTER_PREFIX
					+ filterDriverName + '/' + filterCommandType;
			if(filterCommands != null
					&& filterCommands.contains(builtinFilterCommand)) {
				filterCommand = builtinFilterCommand;
			}
		}
		if(filterCommand != null) {
			filterCommandsByNameDotType.put(key, filterCommand);
		}
		return filterCommand;
	}
}
