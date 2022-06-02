/*
 * Copyright (C) 2008-2009, Google Inc.
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.revwalk;

import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.util.RawParseUtils;
import org.eclipse.jgit.util.StringUtils;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;

import static java.nio.charset.StandardCharsets.UTF_8;

public class RevCommit extends RevObject {
	private static final int STACK_DEPTH = 500;

	public static RevCommit parse(byte[] raw) {
		try {
			return parse(new RevWalk((ObjectReader) null), raw);
		} catch(IOException ex) {
			throw new RuntimeException(ex);
		}
	}

	public static RevCommit parse(RevWalk rw, byte[] raw) throws IOException {
		try(ObjectInserter.Formatter fmt = new ObjectInserter.Formatter()) {
			RevCommit r = rw.lookupCommit(fmt.idFor(Constants.OBJ_COMMIT, raw));
			r.parseCanonical(rw, raw);
			r.buffer = raw;
			return r;
		}
	}

	static final RevCommit[] NO_PARENTS = {};

	private RevTree tree;
	RevCommit[] parents;
	int commitTime;
	int inDegree;
	private byte[] buffer;

	protected RevCommit(AnyObjectId id) {
		super(id);
	}

	@Override
	void parseHeaders(RevWalk walk) throws IOException {
		parseCanonical(walk, walk.getCachedBytes(this));
	}

	@Override
	void parseBody(RevWalk walk) throws IOException {
		if(buffer == null) {
			buffer = walk.getCachedBytes(this);
			if((flags & PARSED) == 0)
				parseCanonical(walk, buffer);
		}
	}

	void parseCanonical(RevWalk walk, byte[] raw) throws IOException {
		if(!walk.shallowCommitsInitialized) {
			walk.initializeShallowCommits(this);
		}

		final MutableObjectId idBuffer = walk.idBuffer;
		idBuffer.fromString(raw, 5);
		tree = walk.lookupTree(idBuffer);

		int ptr = 46;
		if(parents == null) {
			RevCommit[] pList = new RevCommit[1];
			int nParents = 0;
			while(raw[ptr] == 'p') {
				idBuffer.fromString(raw, ptr + 7);
				final RevCommit p = walk.lookupCommit(idBuffer);
				switch(nParents) {
					case 0:
						pList[nParents++] = p;
						break;
					case 1:
						pList = new RevCommit[] {pList[0], p};
						nParents = 2;
						break;
					default:
						if(pList.length <= nParents) {
							RevCommit[] old = pList;
							pList = new RevCommit[pList.length + 32];
							System.arraycopy(old, 0, pList, 0, nParents);
						}
						pList[nParents++] = p;
						break;
				}
				ptr += 48;
			}
			if(nParents != pList.length) {
				RevCommit[] old = pList;
				pList = new RevCommit[nParents];
				System.arraycopy(old, 0, pList, 0, nParents);
			}
			parents = pList;
		}

		ptr = RawParseUtils.committer(raw, ptr);
		if(ptr > 0) {
			ptr = RawParseUtils.nextLF(raw, ptr, '>');

			commitTime = RawParseUtils.parseBase10(raw, ptr, null);
		}

		if(walk.isRetainBody()) {
			buffer = raw;
		}
		flags |= PARSED;
	}

	@Override
	public final int getType() {
		return Constants.OBJ_COMMIT;
	}

	static void carryFlags(RevCommit c, int carry) {
		FIFORevQueue q = carryFlags1(c, carry, 0);
		if(q != null)
			slowCarryFlags(q, carry);
	}

	private static FIFORevQueue carryFlags1(RevCommit c, int carry, int depth) {
		for(; ; ) {
			RevCommit[] pList = c.parents;
			if(pList == null || pList.length == 0)
				return null;
			if(pList.length != 1) {
				if(depth == STACK_DEPTH)
					return defer(c);
				for(int i = 1; i < pList.length; i++) {
					RevCommit p = pList[i];
					if((p.flags & carry) == carry)
						continue;
					p.flags |= carry;
					FIFORevQueue q = carryFlags1(p, carry, depth + 1);
					if(q != null)
						return defer(q, carry, pList, i + 1);
				}
			}

			c = pList[0];
			if((c.flags & carry) == carry)
				return null;
			c.flags |= carry;
		}
	}

	private static FIFORevQueue defer(RevCommit c) {
		FIFORevQueue q = new FIFORevQueue();
		q.add(c);
		return q;
	}

	private static FIFORevQueue defer(FIFORevQueue q, int carry,
									  RevCommit[] pList, int i) {
		carryOneStep(q, carry, pList[0]);

		for(; i < pList.length; i++)
			carryOneStep(q, carry, pList[i]);
		return q;
	}

	private static void slowCarryFlags(FIFORevQueue q, int carry) {
		for(RevCommit c; (c = q.next()) != null; ) {
			for(RevCommit p : c.parents)
				carryOneStep(q, carry, p);
		}
	}

	private static void carryOneStep(FIFORevQueue q, int carry, RevCommit c) {
		if((c.flags & carry) != carry) {
			c.flags |= carry;
			if(c.parents != null)
				q.add(c);
		}
	}

	public void carry(RevFlag flag) {
		final int carry = flags & flag.mask;
		if(carry != 0)
			carryFlags(this, carry);
	}

	public final int getCommitTime() {
		return commitTime;
	}

	public final RevTree getTree() {
		return tree;
	}

	public final int getParentCount() {
		return parents.length;
	}

	public final RevCommit getParent(int nth) {
		return parents[nth];
	}

	public final RevCommit[] getParents() {
		return parents;
	}

	public final byte[] getRawBuffer() {
		return buffer;
	}

	public final PersonIdent getAuthorIdent() {
		final byte[] raw = buffer;
		final int nameB = RawParseUtils.author(raw, 0);
		if(nameB < 0)
			return null;
		return RawParseUtils.parsePersonIdent(raw, nameB);
	}

	public final String getFullMessage() {
		byte[] raw = buffer;
		int msgB = RawParseUtils.commitMessage(raw, 0);
		if(msgB < 0) {
			return "";
		}
		return RawParseUtils.decode(guessEncoding(), raw, msgB, raw.length);
	}

	public final String getShortMessage() {
		byte[] raw = buffer;
		int msgB = RawParseUtils.commitMessage(raw, 0);
		if(msgB < 0) {
			return "";
		}

		int msgE = RawParseUtils.endOfParagraph(raw, msgB);
		String str = RawParseUtils.decode(guessEncoding(), raw, msgB, msgE);
		if(hasLF(raw, msgB, msgE)) {
			str = StringUtils.replaceLineBreaksWithSpace(str);
		}
		return str;
	}

	static boolean hasLF(byte[] r, int b, int e) {
		while(b < e)
			if(r[b++] == '\n')
				return true;
		return false;
	}

	public final Charset getEncoding() {
		return RawParseUtils.parseEncoding(buffer);
	}

	private Charset guessEncoding() {
		try {
			return getEncoding();
		} catch(IllegalCharsetNameException | UnsupportedCharsetException e) {
			return UTF_8;
		}
	}

	public void reset() {
		inDegree = 0;
	}

	public final void disposeBody() {
		buffer = null;
	}

	@Override
	public String toString() {
		final StringBuilder s = new StringBuilder();
		s.append(Constants.typeString(getType()));
		s.append(' ');
		s.append(name());
		s.append(' ');
		s.append(commitTime);
		s.append(' ');
		appendCoreFlags(s);
		return s.toString();
	}
}
