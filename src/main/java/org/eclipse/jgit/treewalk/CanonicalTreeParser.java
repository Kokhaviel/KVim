/*
 * Copyright (C) 2008-2010, Google Inc.
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.treewalk;

import org.eclipse.jgit.attributes.AttributesNode;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.lib.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;

import static org.eclipse.jgit.lib.Constants.*;

public class CanonicalTreeParser extends AbstractTreeIterator {
	private static final byte[] EMPTY = {};
	private static final byte[] ATTRS = encode(DOT_GIT_ATTRIBUTES);

	private byte[] raw;
	private int prevPtr;
	private int currPtr;
	private int nextPtr;

	public CanonicalTreeParser() {
		reset(EMPTY);
	}

	public CanonicalTreeParser(final byte[] prefix, final ObjectReader reader,
			final AnyObjectId treeId) throws IOException {
		super(prefix);
		reset(reader, treeId);
	}

	private CanonicalTreeParser(CanonicalTreeParser p) {
		super(p);
	}

	@Deprecated
	public CanonicalTreeParser getParent() {
		return (CanonicalTreeParser) parent;
	}

	public void reset(byte[] treeData) {
		attributesNode = null;
		raw = treeData;
		prevPtr = -1;
		currPtr = 0;
		if (eof())
			nextPtr = 0;
		else
			parseEntry();
	}

	public CanonicalTreeParser next() {
		CanonicalTreeParser p = this;
		for (;;) {
			if (p.nextPtr == p.raw.length) {
				if (p.parent == null) {
					p.currPtr = p.nextPtr;
					return p;
				}
				p = (CanonicalTreeParser) p.parent;
				continue;
			}

			p.prevPtr = p.currPtr;
			p.currPtr = p.nextPtr;
			p.parseEntry();
			return p;
		}
	}

	public void reset(ObjectReader reader, AnyObjectId id)
			throws IOException {
		reset(reader.open(id, OBJ_TREE).getCachedBytes());
	}

	@Override
	public CanonicalTreeParser createSubtreeIterator(final ObjectReader reader,
			final MutableObjectId idBuffer)
			throws IOException {
		idBuffer.fromRaw(idBuffer(), idOffset());
		if (!FileMode.TREE.equals(mode)) {
			final ObjectId me = idBuffer.toObjectId();
			throw new IncorrectObjectTypeException(me, TYPE_TREE);
		}
		return createSubtreeIterator0(reader, idBuffer);
	}

	public final CanonicalTreeParser createSubtreeIterator0(
			final ObjectReader reader, final AnyObjectId id)
			throws IOException {
		final CanonicalTreeParser p = new CanonicalTreeParser(this);
		p.reset(reader, id);
		return p;
	}

	@Override
	public CanonicalTreeParser createSubtreeIterator(ObjectReader reader)
			throws IOException {
		return createSubtreeIterator(reader, new MutableObjectId());
	}

	@Override
	public boolean hasId() {
		return true;
	}

	@Override
	public byte[] idBuffer() {
		return raw;
	}

	@Override
	public int idOffset() {
		return nextPtr - OBJECT_ID_LENGTH;
	}

	@Override
	public void reset() {
		if (!first())
			reset(raw);
	}

	@Override
	public boolean first() {
		return currPtr == 0;
	}

	@Override
	public boolean eof() {
		return currPtr == raw.length;
	}

	@Override
	public void next(int delta) {
		if (delta == 1) {
			prevPtr = currPtr;
			currPtr = nextPtr;
			if (!eof())
				parseEntry();
			return;
		}

		final int end = raw.length;
		int ptr = nextPtr;
		while (--delta > 0 && ptr != end) {
			prevPtr = ptr;
			while (raw[ptr] != 0)
				ptr++;
			ptr += OBJECT_ID_LENGTH + 1;
		}
		if (delta != 0)
			throw new ArrayIndexOutOfBoundsException(delta);
		currPtr = ptr;
		if (!eof())
			parseEntry();
	}

	@Override
	public void back(int delta) {
		if (delta == 1 && 0 <= prevPtr) {
			currPtr = prevPtr;
			prevPtr = -1;
			if (!eof())
				parseEntry();
			return;
		} else if (delta <= 0)
			throw new ArrayIndexOutOfBoundsException(delta);

		final int[] trace = new int[delta + 1];
		Arrays.fill(trace, -1);
		int ptr = 0;
		while (ptr != currPtr) {
			System.arraycopy(trace, 1, trace, 0, delta);
			trace[delta] = ptr;
			while (raw[ptr] != 0)
				ptr++;
			ptr += OBJECT_ID_LENGTH + 1;
		}
		if (trace[1] == -1)
			throw new ArrayIndexOutOfBoundsException(delta);
		prevPtr = trace[0];
		currPtr = trace[1];
		parseEntry();
	}

	private void parseEntry() {
		int ptr = currPtr;
		byte c = raw[ptr++];
		int tmp = c - '0';
		for (;;) {
			c = raw[ptr++];
			if (' ' == c)
				break;
			tmp <<= 3;
			tmp += c - '0';
		}
		mode = tmp;

		tmp = pathOffset;
		for (;; tmp++) {
			c = raw[ptr++];
			if (c == 0) {
				break;
			}
			if (tmp >= path.length) {
				growPath(tmp);
			}
			path[tmp] = c;
		}
		pathLen = tmp;
		nextPtr = ptr + OBJECT_ID_LENGTH;
	}

	public AttributesNode getEntryAttributesNode(ObjectReader reader)
			throws IOException {
		if (attributesNode == null) {
			attributesNode = findAttributes(reader);
		}
		return attributesNode.getRules().isEmpty() ? null : attributesNode;
	}

	private AttributesNode findAttributes(ObjectReader reader)
			throws IOException {
		CanonicalTreeParser itr = new CanonicalTreeParser();
		itr.reset(raw);
		if (itr.findFile(ATTRS)) {
			return loadAttributes(reader, itr.getEntryObjectId());
		}
		return noAttributes();
	}

	private static AttributesNode loadAttributes(ObjectReader reader,
			AnyObjectId id) throws IOException {
		AttributesNode r = new AttributesNode();
		try (InputStream in = reader.open(id, OBJ_BLOB).openStream()) {
			r.parse(in);
		}
		return r.getRules().isEmpty() ? noAttributes() : r;
	}

	private static AttributesNode noAttributes() {
		return new AttributesNode(Collections.emptyList());
	}
}
