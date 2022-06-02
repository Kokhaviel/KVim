/*
 * Copyright (C) 2008, 2009, Google Inc.
 * Copyright (C) 2008, Marek Zawirski <marek.zawirski@gmail.com>
 * Copyright (C) 2008, 2021, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.revwalk;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;

import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.util.MutableInteger;
import org.eclipse.jgit.util.RawParseUtils;

public class RevTag extends RevObject {

	public static RevTag parse(byte[] raw) throws CorruptObjectException {
		return parse(new RevWalk((ObjectReader) null), raw);
	}

	public static RevTag parse(RevWalk rw, byte[] raw)
			throws CorruptObjectException {
		try(ObjectInserter.Formatter fmt = new ObjectInserter.Formatter()) {
			RevTag r = rw.lookupTag(fmt.idFor(Constants.OBJ_TAG, raw));
			r.parseCanonical(rw, raw);
			r.buffer = raw;
			return r;
		}
	}

	private RevObject object;

	private byte[] buffer;

	protected RevTag(AnyObjectId id) {
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

	void parseCanonical(RevWalk walk, byte[] rawTag)
			throws CorruptObjectException {
		final MutableInteger pos = new MutableInteger();
		final int oType;

		pos.value = 53;
		oType = Constants.decodeTypeString(this, rawTag, (byte) '\n', pos);
		walk.idBuffer.fromString(rawTag, 7);
		object = walk.lookupAny(walk.idBuffer, oType);

		int p = pos.value += 4;
		final int nameEnd = RawParseUtils.nextLF(rawTag, p) - 1;
		RawParseUtils.decode(UTF_8, rawTag, p, nameEnd);

		if(walk.isRetainBody())
			buffer = rawTag;
		flags |= PARSED;
	}

	@Override
	public final int getType() {
		return Constants.OBJ_TAG;
	}

	public final PersonIdent getTaggerIdent() {
		final byte[] raw = buffer;
		final int nameB = RawParseUtils.tagger(raw, 0);
		if(nameB < 0)
			return null;
		return RawParseUtils.parsePersonIdent(raw, nameB);
	}

	public final RevObject getObject() {
		return object;
	}

}
