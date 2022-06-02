/*
 * Copyright (C) 2008, Google Inc.
 * Copyright (C) 2008, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2006-2017, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.lib;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.MessageFormat;

import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.util.MutableInteger;

public final class Constants {

	private static final String HASH_FUNCTION = "SHA-1";

	public static final int OBJECT_ID_LENGTH = 20;
	public static final int OBJECT_ID_STRING_LENGTH = OBJECT_ID_LENGTH * 2;
	public static final int OBJECT_ID_ABBREV_STRING_LENGTH = 7;

	public static final String HEAD = "HEAD";
	public static final String FETCH_HEAD = "FETCH_HEAD";
	public static final String TYPE_COMMIT = "commit";
	public static final String TYPE_BLOB = "blob";
	public static final String TYPE_TREE = "tree";
	public static final String TYPE_TAG = "tag";

	private static final byte[] ENCODED_TYPE_COMMIT = encodeASCII(TYPE_COMMIT);
	private static final byte[] ENCODED_TYPE_BLOB = encodeASCII(TYPE_BLOB);
	private static final byte[] ENCODED_TYPE_TREE = encodeASCII(TYPE_TREE);
	private static final byte[] ENCODED_TYPE_TAG = encodeASCII(TYPE_TAG);

	public static final int OBJ_BAD = -1;
	public static final int OBJ_COMMIT = 1;
	public static final int OBJ_TREE = 2;
	public static final int OBJ_BLOB = 3;
	public static final int OBJ_TAG = 4;
	public static final int OBJ_OFS_DELTA = 6;
	public static final int OBJ_REF_DELTA = 7;

	public static final byte[] PACK_SIGNATURE = {'P', 'A', 'C', 'K'};

	public static final Charset CHARSET;
	public static final String CHARACTER_ENCODING;

	public static final String MASTER = "master";
	public static final String STASH = "stash";
	public static final String R_HEADS = "refs/heads/";
	public static final String R_REMOTES = "refs/remotes/";
	public static final String R_TAGS = "refs/tags/";
	public static final String R_NOTES = "refs/notes/";
	public static final String R_REFS = "refs/";
	public static final String R_STASH = R_REFS + STASH;
	public static final String LOGS = "logs";
	public static final String OBJECTS = "objects";
	public static final String REFTABLE = "reftable";
	public static final String TABLES_LIST = "tables.list";
	public static final String INFO_REFS = "info/refs";
	public static final String INFO_ALTERNATES = "info/alternates";
	public static final String INFO_HTTP_ALTERNATES = "info/http-alternates";
	public static final String PACKED_REFS = "packed-refs";
	public static final String INFO_EXCLUDE = "info/exclude";
	public static final String INFO_ATTRIBUTES = "info/attributes";
	public static final String OS_USER_NAME_KEY = "user.name";
	public static final String GIT_AUTHOR_EMAIL_KEY = "GIT_AUTHOR_EMAIL";
	public static final String GIT_COMMITTER_NAME_KEY = "GIT_COMMITTER_NAME";
	public static final String GIT_COMMITTER_EMAIL_KEY = "GIT_COMMITTER_EMAIL";
	public static final String GIT_CONFIG_NOSYSTEM_KEY = "GIT_CONFIG_NOSYSTEM";
	public static final String XDG_CONFIG_HOME = "XDG_CONFIG_HOME";
	public static final String GIT_CEILING_DIRECTORIES_KEY = "GIT_CEILING_DIRECTORIES";
	public static final String GIT_DIR_KEY = "GIT_DIR";
	public static final String GIT_WORK_TREE_KEY = "GIT_WORK_TREE";
	public static final String GIT_INDEX_FILE_KEY = "GIT_INDEX_FILE";
	public static final String GIT_OBJECT_DIRECTORY_KEY = "GIT_OBJECT_DIRECTORY";
	public static final String GIT_ALTERNATE_OBJECT_DIRECTORIES_KEY = "GIT_ALTERNATE_OBJECT_DIRECTORIES";
	public static final String UNKNOWN_USER_DEFAULT = "unknown-user";
	public static final String DEFAULT_REMOTE_NAME = "origin";
	public static final String DOT_GIT = ".git";
	public static final String CONFIG = "config";
	public static final String DOT_GIT_EXT = ".git";
	public static final String DOT_BUNDLE_EXT = ".bundle";
	public static final String DOT_GIT_ATTRIBUTES = ".gitattributes";
	public static final String ATTR_FILTER = "filter";
	public static final String ATTR_FILTER_TYPE_CLEAN = "clean";
	public static final String ATTR_FILTER_TYPE_SMUDGE = "smudge";
	public static final String BUILTIN_FILTER_PREFIX = "jgit://builtin/";
	public static final String DOT_GIT_IGNORE = ".gitignore";
	public static final String DOT_GIT_MODULES = ".gitmodules";
	public static final String SHALLOW = "shallow";
	public static final String GITDIR = "gitdir: ";
	public static final String MODULES = "modules";
	public static final String HOOKS = "hooks";
	public static final String ATTR_MERGE = "merge";
	public static final String ATTR_DIFF = "diff";
	public static final String ATTR_BUILTIN_BINARY_MERGER = "binary";

	public static MessageDigest newMessageDigest() {
		try {
			return MessageDigest.getInstance(HASH_FUNCTION);
		} catch(NoSuchAlgorithmException nsae) {
			throw new RuntimeException(MessageFormat.format(
					JGitText.get().requiredHashFunctionNotAvailable, HASH_FUNCTION), nsae);
		}
	}

	public static String typeString(int typeCode) {
		switch(typeCode) {
			case OBJ_COMMIT:
				return TYPE_COMMIT;
			case OBJ_TREE:
				return TYPE_TREE;
			case OBJ_BLOB:
				return TYPE_BLOB;
			case OBJ_TAG:
				return TYPE_TAG;
			default:
				throw new IllegalArgumentException(MessageFormat.format(
						JGitText.get().badObjectType, typeCode));
		}
	}

	public static byte[] encodedTypeString(int typeCode) {
		switch(typeCode) {
			case OBJ_COMMIT:
				return ENCODED_TYPE_COMMIT;
			case OBJ_TREE:
				return ENCODED_TYPE_TREE;
			case OBJ_BLOB:
				return ENCODED_TYPE_BLOB;
			case OBJ_TAG:
				return ENCODED_TYPE_TAG;
			default:
				throw new IllegalArgumentException(MessageFormat.format(
						JGitText.get().badObjectType, typeCode));
		}
	}

	public static int decodeTypeString(final AnyObjectId id,
									   final byte[] typeString, final byte endMark,
									   final MutableInteger offset) throws CorruptObjectException {
		try {
			int position = offset.value;
			switch(typeString[position]) {
				case 'b':
					if(typeString[position + 1] != 'l'
							|| typeString[position + 2] != 'o'
							|| typeString[position + 3] != 'b'
							|| typeString[position + 4] != endMark)
						throw new CorruptObjectException(id, JGitText.get().corruptObjectInvalidType);
					offset.value = position + 5;
					return Constants.OBJ_BLOB;

				case 'c':
					if(typeString[position + 1] != 'o'
							|| typeString[position + 2] != 'm'
							|| typeString[position + 3] != 'm'
							|| typeString[position + 4] != 'i'
							|| typeString[position + 5] != 't'
							|| typeString[position + 6] != endMark)
						throw new CorruptObjectException(id, JGitText.get().corruptObjectInvalidType);
					offset.value = position + 7;
					return Constants.OBJ_COMMIT;

				case 't':
					switch(typeString[position + 1]) {
						case 'a':
							if(typeString[position + 2] != 'g'
									|| typeString[position + 3] != endMark)
								throw new CorruptObjectException(id, JGitText.get().corruptObjectInvalidType);
							offset.value = position + 4;
							return Constants.OBJ_TAG;

						case 'r':
							if(typeString[position + 2] != 'e'
									|| typeString[position + 3] != 'e'
									|| typeString[position + 4] != endMark)
								throw new CorruptObjectException(id, JGitText.get().corruptObjectInvalidType);
							offset.value = position + 5;
							return Constants.OBJ_TREE;

						default:
							throw new CorruptObjectException(id, JGitText.get().corruptObjectInvalidType);
					}

				default:
					throw new CorruptObjectException(id, JGitText.get().corruptObjectInvalidType);
			}
		} catch(ArrayIndexOutOfBoundsException bad) {
			CorruptObjectException coe = new CorruptObjectException(id,
					JGitText.get().corruptObjectInvalidType);
			coe.initCause(bad);
			throw coe;
		}
	}

	public static byte[] encodeASCII(long s) {
		return encodeASCII(Long.toString(s));
	}

	public static byte[] encodeASCII(String s) {
		final byte[] r = new byte[s.length()];
		for(int k = r.length - 1; k >= 0; k--) {
			final char c = s.charAt(k);
			if(c > 127)
				throw new IllegalArgumentException(MessageFormat.format(JGitText.get().notASCIIString, s));
			r[k] = (byte) c;
		}
		return r;
	}

	public static byte[] encode(String str) {
		final ByteBuffer bb = UTF_8.encode(str);
		final int len = bb.limit();
		if(bb.hasArray() && bb.arrayOffset() == 0) {
			final byte[] arr = bb.array();
			if(arr.length == len)
				return arr;
		}

		final byte[] arr = new byte[len];
		bb.get(arr);
		return arr;
	}

	static {
		if(OBJECT_ID_LENGTH != newMessageDigest().getDigestLength())
			throw new LinkageError(JGitText.get().incorrectOBJECT_ID_LENGTH);
		CHARSET = UTF_8;
		CHARACTER_ENCODING = UTF_8.name();
	}

	public static final String MERGE_MSG = "MERGE_MSG";
	public static final String MERGE_HEAD = "MERGE_HEAD";
	public static final String CHERRY_PICK_HEAD = "CHERRY_PICK_HEAD";
	public static final String SQUASH_MSG = "SQUASH_MSG";
	public static final String REVERT_HEAD = "REVERT_HEAD";
	public static final String ORIG_HEAD = "ORIG_HEAD";
	public static final String COMMIT_EDITMSG = "COMMIT_EDITMSG";
	public static final ObjectId EMPTY_BLOB_ID = ObjectId
			.fromString("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391");
	public static final String LOCK_SUFFIX = ".lock";

	private Constants() {
	}
}
