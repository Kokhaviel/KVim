/*
 * Copyright (C) 2009, Google Inc.
 * Copyright (C) 2008-2020, Johannes E. Schindelin <johannes.schindelin@gmx.de> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.diff;

import org.eclipse.jgit.api.errors.CanceledException;
import org.eclipse.jgit.diff.DiffAlgorithm.SupportedAlgorithm;
import org.eclipse.jgit.diff.DiffEntry.ChangeType;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.errors.AmbiguousObjectException;
import org.eclipse.jgit.errors.BinaryBlobException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.patch.FileHeader.PatchType;
import org.eclipse.jgit.revwalk.FollowFilter;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.pack.PackConfig;
import org.eclipse.jgit.treewalk.*;
import org.eclipse.jgit.treewalk.filter.*;
import org.eclipse.jgit.util.LfsFactory;
import org.eclipse.jgit.util.QuotedString;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.eclipse.jgit.diff.DiffEntry.ChangeType.*;
import static org.eclipse.jgit.diff.DiffEntry.Side.NEW;
import static org.eclipse.jgit.diff.DiffEntry.Side.OLD;
import static org.eclipse.jgit.lib.Constants.*;
import static org.eclipse.jgit.lib.FileMode.GITLINK;

public class DiffFormatter implements AutoCloseable {

	private static final int DEFAULT_BINARY_FILE_THRESHOLD = PackConfig.DEFAULT_BIG_FILE_THRESHOLD;
	private static final byte[] noNewLine = encodeASCII("\\ No newline at end of file\n");
	private static final byte[] EMPTY = new byte[] {};

	private final OutputStream out;
	private ObjectReader reader;
	private boolean closeReader;
	private DiffConfig diffCfg;
	private final int context = 3;
	private DiffAlgorithm diffAlgorithm;
	private final RawTextComparator comparator = RawTextComparator.DEFAULT;
	private String oldPrefix = "a/";
	private String newPrefix = "b/";
	private TreeFilter pathFilter = TreeFilter.ALL;
	private RenameDetector renameDetector;
	private ProgressMonitor progressMonitor;
	private ContentSource.Pair source;
	private Repository repository;
	private Boolean quotePaths;

	public DiffFormatter(OutputStream out) {
		this.out = out;
	}

	public void setRepository(Repository repository) {
		this.repository = repository;
		setReader(repository.newObjectReader(), repository.getConfig());
	}

	private void setReader(ObjectReader reader, Config cfg) {
		close();
		this.closeReader = true;
		this.reader = reader;
		this.diffCfg = cfg.get(DiffConfig.KEY);
		if(quotePaths == null) {
			quotePaths = cfg.getBoolean(ConfigConstants.CONFIG_CORE_SECTION,
					ConfigConstants.CONFIG_KEY_QUOTE_PATH, true);
		}

		ContentSource cs = ContentSource.create(reader);
		source = new ContentSource.Pair(cs, cs);

		if(diffCfg.isNoPrefix()) {
			setOldPrefix("");
			setNewPrefix("");
		}
		setDetectRenames(diffCfg.isRenameDetectionEnabled());

		diffAlgorithm = DiffAlgorithm.getAlgorithm(cfg.getEnum(
				ConfigConstants.CONFIG_DIFF_SECTION, null,
				ConfigConstants.CONFIG_KEY_ALGORITHM,
				SupportedAlgorithm.HISTOGRAM));
	}

	public void setOldPrefix(String prefix) {
		oldPrefix = prefix;
	}

	public void setNewPrefix(String prefix) {
		newPrefix = prefix;
	}

	public void setDetectRenames(boolean on) {
		if(on && renameDetector == null) {
			assertHaveReader();
			renameDetector = new RenameDetector(reader, diffCfg);
		} else if(!on)
			renameDetector = null;
	}

	public void setProgressMonitor(ProgressMonitor pm) {
		progressMonitor = pm;
	}

	public void setPathFilter(TreeFilter filter) {
		pathFilter = filter != null ? filter : TreeFilter.ALL;
	}

	public void flush() throws IOException {
		out.flush();
	}

	@Override
	public void close() {
		if(reader != null && closeReader) {
			reader.close();
		}
	}

	public List<DiffEntry> scan(AnyObjectId a, AnyObjectId b)
			throws IOException {
		assertHaveReader();

		try(RevWalk rw = new RevWalk(reader)) {
			RevTree aTree = a != null ? rw.parseTree(a) : null;
			RevTree bTree = b != null ? rw.parseTree(b) : null;
			return scan(aTree, bTree);
		}
	}

	public List<DiffEntry> scan(RevTree a, RevTree b) throws IOException {
		assertHaveReader();

		AbstractTreeIterator aIterator = makeIteratorFromTreeOrNull(a);
		AbstractTreeIterator bIterator = makeIteratorFromTreeOrNull(b);
		return scan(aIterator, bIterator);
	}

	private AbstractTreeIterator makeIteratorFromTreeOrNull(RevTree tree)
			throws IOException {
		if(tree != null) {
			CanonicalTreeParser parser = new CanonicalTreeParser();
			parser.reset(reader, tree);
			return parser;
		}
		return new EmptyTreeIterator();
	}

	public List<DiffEntry> scan(AbstractTreeIterator a, AbstractTreeIterator b)
			throws IOException {
		assertHaveReader();

		TreeWalk walk = new TreeWalk(repository, reader);
		int aIndex = walk.addTree(a);
		int bIndex = walk.addTree(b);
		if(repository != null) {
			if(a instanceof WorkingTreeIterator && b instanceof DirCacheIterator) {
				a.setDirCacheIterator(walk, bIndex);
			} else if(b instanceof WorkingTreeIterator && a instanceof DirCacheIterator) {
				b.setDirCacheIterator(walk, aIndex);
			}
		}
		walk.setRecursive(true);

		TreeFilter filter = getDiffTreeFilterFor(a, b);
		if(pathFilter instanceof FollowFilter) {
			walk.setFilter(AndTreeFilter.create(
					PathFilter.create(((FollowFilter) pathFilter).getPath()),
					filter));
		} else {
			walk.setFilter(AndTreeFilter.create(pathFilter, filter));
		}

		source = new ContentSource.Pair(source(a), source(b));

		List<DiffEntry> files = DiffEntry.scan(walk);
		if(pathFilter instanceof FollowFilter && isAdd(files)) {
			a.reset();
			b.reset();
			walk.reset();
			walk.addTree(a);
			walk.addTree(b);
			walk.setFilter(filter);

			if(renameDetector == null)
				setDetectRenames(true);
			files = updateFollowFilter(detectRenames(DiffEntry.scan(walk)));

		} else if(renameDetector != null)
			files = detectRenames(files);

		return files;
	}

	private static TreeFilter getDiffTreeFilterFor(AbstractTreeIterator a,
												   AbstractTreeIterator b) {
		if(a instanceof DirCacheIterator && b instanceof WorkingTreeIterator)
			return new IndexDiffFilter(0, 1);

		if(a instanceof WorkingTreeIterator && b instanceof DirCacheIterator)
			return new IndexDiffFilter(1, 0);

		TreeFilter filter = TreeFilter.ANY_DIFF;
		if(a instanceof WorkingTreeIterator)
			filter = AndTreeFilter.create(new NotIgnoredFilter(0), filter);
		if(b instanceof WorkingTreeIterator)
			filter = AndTreeFilter.create(new NotIgnoredFilter(1), filter);
		return filter;
	}

	private ContentSource source(AbstractTreeIterator iterator) {
		if(iterator instanceof WorkingTreeIterator)
			return ContentSource.create((WorkingTreeIterator) iterator);
		return ContentSource.create(reader);
	}

	private List<DiffEntry> detectRenames(List<DiffEntry> files)
			throws IOException {
		renameDetector.reset();
		renameDetector.addAll(files);
		try {
			return renameDetector.compute(reader, progressMonitor);
		} catch(CanceledException e) {
			return Collections.emptyList();
		}
	}

	private boolean isAdd(List<DiffEntry> files) {
		String oldPath = ((FollowFilter) pathFilter).getPath();
		for(DiffEntry ent : files) {
			if(ent.getChangeType() == ADD && ent.getNewPath().equals(oldPath))
				return true;
		}
		return false;
	}

	private List<DiffEntry> updateFollowFilter(List<DiffEntry> files) {
		String oldPath = ((FollowFilter) pathFilter).getPath();
		for(DiffEntry ent : files) {
			if(isRename(ent) && ent.getNewPath().equals(oldPath)) {
				pathFilter = FollowFilter.create(ent.getOldPath(), diffCfg);
				return Collections.singletonList(ent);
			}
		}
		return Collections.emptyList();
	}

	private static boolean isRename(DiffEntry ent) {
		return ent.getChangeType() == RENAME || ent.getChangeType() == COPY;
	}

	public void format(AnyObjectId a, AnyObjectId b) throws IOException {
		format(scan(a, b));
	}

	public void format(List<? extends DiffEntry> entries) throws IOException {
		for(DiffEntry ent : entries)
			format(ent);
	}

	public void format(DiffEntry ent) throws IOException {
		FormatResult res = createFormatResult(ent);
		format(res.header, res.a, res.b);
	}

	private static byte[] writeGitLinkText(AbbreviatedObjectId id) {
		if(ObjectId.zeroId().equals(id.toObjectId())) {
			return EMPTY;
		}
		return encodeASCII("Subproject commit " + id.name()
				+ "\n");
	}

	private String format(AbbreviatedObjectId id) {
		if(id.isComplete() && reader != null) {
			try {
				id = reader.abbreviate(id.toObjectId(), OBJECT_ID_ABBREV_STRING_LENGTH);
			} catch(IOException ignored) {
			}
		}
		return id.name();
	}

	private String quotePath(String path) {
		if(quotePaths == null || quotePaths) {
			return QuotedString.GIT_PATH.quote(path);
		}
		return QuotedString.GIT_PATH_MINIMAL.quote(path);
	}

	public void format(FileHeader head, RawText a, RawText b) throws IOException {
		final int start = head.getStartOffset();
		int end = head.getEndOffset();
		if(!head.getHunks().isEmpty())
			end = head.getHunks().get(0).getStartOffset();
		out.write(head.getBuffer(), start, end - start);
		if(head.getPatchType() == PatchType.UNIFIED)
			format(head.toEditList(), a, b);
	}

	public void format(EditList edits, RawText a, RawText b)
			throws IOException {
		for(int curIdx = 0; curIdx < edits.size(); ) {
			Edit curEdit = edits.get(curIdx);
			final int endIdx = findCombinedEnd(edits, curIdx);
			final Edit endEdit = edits.get(endIdx);

			int aCur = (int) Math.max(0, (long) curEdit.getBeginA() - context);
			int bCur = (int) Math.max(0, (long) curEdit.getBeginB() - context);
			final int aEnd = (int) Math.min(a.size(), (long) endEdit.getEndA() + context);
			final int bEnd = (int) Math.min(b.size(), (long) endEdit.getEndB() + context);

			writeHunkHeader(aCur, aEnd, bCur, bEnd);

			while(aCur < aEnd || bCur < bEnd) {
				if(aCur < curEdit.getBeginA() || endIdx + 1 < curIdx) {
					writeContextLine(a, aCur);
					if(isEndOfLineMissing(a, aCur))
						out.write(noNewLine);
					aCur++;
					bCur++;
				} else if(aCur < curEdit.getEndA()) {
					writeRemovedLine(a, aCur);
					if(isEndOfLineMissing(a, aCur))
						out.write(noNewLine);
					aCur++;
				} else if(bCur < curEdit.getEndB()) {
					writeAddedLine(b, bCur);
					if(isEndOfLineMissing(b, bCur))
						out.write(noNewLine);
					bCur++;
				}

				if(end(curEdit, aCur, bCur) && ++curIdx < edits.size())
					curEdit = edits.get(curIdx);
			}
		}
	}

	protected void writeContextLine(RawText text, int line) throws IOException {
		writeLine(' ', text, line);
	}

	private static boolean isEndOfLineMissing(RawText text, int line) {
		return line + 1 == text.size() && text.isMissingNewlineAtEnd();
	}

	protected void writeAddedLine(RawText text, int line) throws IOException {
		writeLine('+', text, line);
	}

	protected void writeRemovedLine(RawText text, int line) throws IOException {
		writeLine('-', text, line);
	}

	protected void writeHunkHeader(int aStartLine, int aEndLine,
								   int bStartLine, int bEndLine) throws IOException {
		out.write('@');
		out.write('@');
		writeRange('-', aStartLine + 1, aEndLine - aStartLine);
		writeRange('+', bStartLine + 1, bEndLine - bStartLine);
		out.write(' ');
		out.write('@');
		out.write('@');
		out.write('\n');
	}

	private void writeRange(char prefix, int begin, int cnt)
			throws IOException {
		out.write(' ');
		out.write(prefix);
		switch(cnt) {
			case 0:
				out.write(encodeASCII(begin - 1));
				out.write(',');
				out.write('0');
				break;
			case 1:
				out.write(encodeASCII(begin));
				break;
			default:
				out.write(encodeASCII(begin));
				out.write(',');
				out.write(encodeASCII(cnt));
				break;
		}
	}

	protected void writeLine(final char prefix, final RawText text,
							 final int cur) throws IOException {
		out.write(prefix);
		text.writeLine(out, cur);
		out.write('\n');
	}

	private static class FormatResult {
		FileHeader header;

		RawText a;

		RawText b;
	}

	private FormatResult createFormatResult(DiffEntry ent) throws IOException {
		final FormatResult res = new FormatResult();
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		final EditList editList;
		final FileHeader.PatchType type;

		formatHeader(buf, ent);

		if(ent.getOldId() == null || ent.getNewId() == null) {
			editList = new EditList();
			type = PatchType.UNIFIED;
			res.header = new FileHeader(buf.toByteArray(), editList, type);
			return res;
		}

		assertHaveReader();

		RawText aRaw;
		RawText bRaw;
		if(ent.getOldMode() == GITLINK || ent.getNewMode() == GITLINK) {
			aRaw = new RawText(writeGitLinkText(ent.getOldId()));
			bRaw = new RawText(writeGitLinkText(ent.getNewId()));
		} else {
			try {
				aRaw = open(OLD, ent);
				bRaw = open(NEW, ent);
			} catch(BinaryBlobException e) {
				formatOldNewPaths(buf, ent);
				buf.write(encodeASCII("Binary files differ\n"));
				editList = new EditList();
				type = PatchType.BINARY;
				res.header = new FileHeader(buf.toByteArray(), editList, type);
				return res;
			}
		}

		res.a = aRaw;
		res.b = bRaw;
		editList = diff(res.a, res.b);
		type = PatchType.UNIFIED;

		switch(ent.getChangeType()) {
			case RENAME:
			case COPY:
				if(!editList.isEmpty())
					formatOldNewPaths(buf, ent);
				break;

			default:
				formatOldNewPaths(buf, ent);
				break;
		}


		res.header = new FileHeader(buf.toByteArray(), editList, type);
		return res;
	}

	private EditList diff(RawText a, RawText b) {
		return diffAlgorithm.diff(comparator, a, b);
	}

	private void assertHaveReader() {
		if(reader == null) {
			throw new IllegalStateException(JGitText.get().readerIsRequired);
		}
	}

	private RawText open(DiffEntry.Side side, DiffEntry entry)
			throws IOException, BinaryBlobException {
		if(entry.getMode(side) == FileMode.MISSING)
			return RawText.EMPTY_TEXT;

		if(entry.getMode(side).getObjectType() != Constants.OBJ_BLOB)
			return RawText.EMPTY_TEXT;

		AbbreviatedObjectId id = entry.getId(side);
		if(!id.isComplete()) {
			Collection<ObjectId> ids = reader.resolve(id);
			if(ids.size() == 1) {
				id = AbbreviatedObjectId.fromObjectId(ids.iterator().next());
				switch(side) {
					case OLD:
						entry.oldId = id;
						break;
					case NEW:
						entry.newId = id;
						break;
				}
			} else if(ids.isEmpty())
				throw new MissingObjectException(id, Constants.OBJ_BLOB);
			else
				throw new AmbiguousObjectException(id);
		}

		ObjectLoader ldr = LfsFactory.getInstance().applySmudgeFilter(
				source.open(side, entry), entry.getDiffAttribute());
		return RawText.load(ldr, DEFAULT_BINARY_FILE_THRESHOLD);
	}

	protected void formatGitDiffFirstHeaderLine(ByteArrayOutputStream o,
												final ChangeType type, final String oldPath, final String newPath) throws IOException {
		o.write(encodeASCII("diff --git "));
		o.write(encode(quotePath(oldPrefix + (type == ADD ? newPath : oldPath))));
		o.write(' ');
		o.write(encode(quotePath(newPrefix
				+ (type == DELETE ? oldPath : newPath))));
		o.write('\n');
	}

	private void formatHeader(ByteArrayOutputStream o, DiffEntry ent) throws IOException {
		final ChangeType type = ent.getChangeType();
		final String oldp = ent.getOldPath();
		final String newp = ent.getNewPath();
		final FileMode oldMode = ent.getOldMode();
		final FileMode newMode = ent.getNewMode();

		formatGitDiffFirstHeaderLine(o, type, oldp, newp);

		if((type == MODIFY || type == COPY || type == RENAME)
				&& !oldMode.equals(newMode)) {
			o.write(encodeASCII("old mode "));
			oldMode.copyTo(o);
			o.write('\n');

			o.write(encodeASCII("new mode "));
			newMode.copyTo(o);
			o.write('\n');
		}

		switch(type) {
			case ADD:
				o.write(encodeASCII("new file mode "));
				newMode.copyTo(o);
				o.write('\n');
				break;

			case DELETE:
				o.write(encodeASCII("deleted file mode "));
				oldMode.copyTo(o);
				o.write('\n');
				break;

			case RENAME:
				o.write(encodeASCII("similarity index " + ent.getScore() + "%"));
				o.write('\n');

				o.write(encode("rename from " + quotePath(oldp)));
				o.write('\n');

				o.write(encode("rename to " + quotePath(newp)));
				o.write('\n');
				break;

			case COPY:
				o.write(encodeASCII("similarity index " + ent.getScore() + "%"));
				o.write('\n');

				o.write(encode("copy from " + quotePath(oldp)));
				o.write('\n');

				o.write(encode("copy to " + quotePath(newp)));
				o.write('\n');
				break;

			case MODIFY:
				if(0 < ent.getScore()) {
					o.write(encodeASCII("dissimilarity index "
							+ (100 - ent.getScore()) + "%"));
					o.write('\n');
				}
				break;
		}

		if(ent.getOldId() != null && !ent.getOldId().equals(ent.getNewId())) {
			formatIndexLine(o, ent);
		}
	}

	protected void formatIndexLine(OutputStream o, DiffEntry ent)
			throws IOException {
		o.write(encodeASCII("index " + format(ent.getOldId()) + ".." + format(ent.getNewId())));
		if(ent.getOldMode().equals(ent.getNewMode())) {
			o.write(' ');
			ent.getNewMode().copyTo(o);
		}
		o.write('\n');
	}

	private void formatOldNewPaths(ByteArrayOutputStream o, DiffEntry ent)
			throws IOException {
		if(ent.oldId.equals(ent.newId))
			return;

		final String oldp;
		final String newp;

		switch(ent.getChangeType()) {
			case ADD:
				oldp = DiffEntry.DEV_NULL;
				newp = quotePath(newPrefix + ent.getNewPath());
				break;

			case DELETE:
				oldp = quotePath(oldPrefix + ent.getOldPath());
				newp = DiffEntry.DEV_NULL;
				break;

			default:
				oldp = quotePath(oldPrefix + ent.getOldPath());
				newp = quotePath(newPrefix + ent.getNewPath());
				break;
		}

		o.write(encode("--- " + oldp + "\n"));
		o.write(encode("+++ " + newp + "\n"));
	}

	private int findCombinedEnd(List<Edit> edits, int i) {
		int end = i + 1;
		while(end < edits.size()
				&& (combineA(edits, end) || combineB(edits, end)))
			end++;
		return end - 1;
	}

	private boolean combineA(List<Edit> e, int i) {
		return e.get(i).getBeginA() - e.get(i - 1).getEndA() <= 2 * context;
	}

	private boolean combineB(List<Edit> e, int i) {
		return e.get(i).getBeginB() - e.get(i - 1).getEndB() <= 2 * context;
	}

	private static boolean end(Edit edit, int a, int b) {
		return edit.getEndA() <= a && edit.getEndB() <= b;
	}
}
