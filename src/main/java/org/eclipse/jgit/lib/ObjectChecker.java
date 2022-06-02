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

package org.eclipse.jgit.lib;

import static org.eclipse.jgit.lib.Constants.DOT_GIT_MODULES;
import static org.eclipse.jgit.lib.Constants.OBJECT_ID_LENGTH;
import static org.eclipse.jgit.lib.Constants.OBJECT_ID_STRING_LENGTH;
import static org.eclipse.jgit.lib.Constants.OBJ_BAD;
import static org.eclipse.jgit.lib.Constants.OBJ_BLOB;
import static org.eclipse.jgit.lib.Constants.OBJ_COMMIT;
import static org.eclipse.jgit.lib.Constants.OBJ_TAG;
import static org.eclipse.jgit.lib.Constants.OBJ_TREE;
import static org.eclipse.jgit.lib.ObjectChecker.ErrorType.BAD_DATE;
import static org.eclipse.jgit.lib.ObjectChecker.ErrorType.BAD_EMAIL;
import static org.eclipse.jgit.lib.ObjectChecker.ErrorType.BAD_OBJECT_SHA1;
import static org.eclipse.jgit.lib.ObjectChecker.ErrorType.BAD_PARENT_SHA1;
import static org.eclipse.jgit.lib.ObjectChecker.ErrorType.BAD_TIMEZONE;
import static org.eclipse.jgit.lib.ObjectChecker.ErrorType.BAD_TREE_SHA1;
import static org.eclipse.jgit.lib.ObjectChecker.ErrorType.BAD_UTF8;
import static org.eclipse.jgit.lib.ObjectChecker.ErrorType.DUPLICATE_ENTRIES;
import static org.eclipse.jgit.lib.ObjectChecker.ErrorType.EMPTY_NAME;
import static org.eclipse.jgit.lib.ObjectChecker.ErrorType.FULL_PATHNAME;
import static org.eclipse.jgit.lib.ObjectChecker.ErrorType.HAS_DOT;
import static org.eclipse.jgit.lib.ObjectChecker.ErrorType.HAS_DOTDOT;
import static org.eclipse.jgit.lib.ObjectChecker.ErrorType.HAS_DOTGIT;
import static org.eclipse.jgit.lib.ObjectChecker.ErrorType.MISSING_AUTHOR;
import static org.eclipse.jgit.lib.ObjectChecker.ErrorType.MISSING_COMMITTER;
import static org.eclipse.jgit.lib.ObjectChecker.ErrorType.MISSING_EMAIL;
import static org.eclipse.jgit.lib.ObjectChecker.ErrorType.MISSING_OBJECT;
import static org.eclipse.jgit.lib.ObjectChecker.ErrorType.MISSING_SPACE_BEFORE_DATE;
import static org.eclipse.jgit.lib.ObjectChecker.ErrorType.MISSING_TAG_ENTRY;
import static org.eclipse.jgit.lib.ObjectChecker.ErrorType.MISSING_TREE;
import static org.eclipse.jgit.lib.ObjectChecker.ErrorType.MISSING_TYPE_ENTRY;
import static org.eclipse.jgit.lib.ObjectChecker.ErrorType.NULL_SHA1;
import static org.eclipse.jgit.lib.ObjectChecker.ErrorType.TREE_NOT_SORTED;
import static org.eclipse.jgit.lib.ObjectChecker.ErrorType.UNKNOWN_TYPE;
import static org.eclipse.jgit.lib.ObjectChecker.ErrorType.WIN32_BAD_NAME;
import static org.eclipse.jgit.lib.ObjectChecker.ErrorType.ZERO_PADDED_FILEMODE;
import static org.eclipse.jgit.util.Paths.compare;
import static org.eclipse.jgit.util.Paths.compareSameName;
import static org.eclipse.jgit.util.RawParseUtils.nextLF;
import static org.eclipse.jgit.util.RawParseUtils.parseBase10;

import java.text.MessageFormat;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.util.MutableInteger;
import org.eclipse.jgit.util.RawParseUtils;
import org.eclipse.jgit.util.StringUtils;

public class ObjectChecker {

	public static final byte[] tree = Constants.encodeASCII("tree ");
	public static final byte[] parent = Constants.encodeASCII("parent ");
	public static final byte[] author = Constants.encodeASCII("author ");
	public static final byte[] committer = Constants.encodeASCII("committer ");
	public static final byte[] encoding = Constants.encodeASCII("encoding ");
	public static final byte[] object = Constants.encodeASCII("object ");
	public static final byte[] type = Constants.encodeASCII("type ");
	public static final byte[] tag = Constants.encodeASCII("tag ");
	public static final byte[] tagger = Constants.encodeASCII("tagger ");
	private static final byte[] dotGitmodules = Constants.encodeASCII(DOT_GIT_MODULES);

	public enum ErrorType {
		NULL_SHA1,
		DUPLICATE_ENTRIES,
		TREE_NOT_SORTED,
		ZERO_PADDED_FILEMODE,
		EMPTY_NAME,
		FULL_PATHNAME,
		HAS_DOT,
		HAS_DOTDOT,
		HAS_DOTGIT,
		BAD_OBJECT_SHA1,
		BAD_PARENT_SHA1,
		BAD_TREE_SHA1,
		MISSING_AUTHOR,
		MISSING_COMMITTER,
		MISSING_OBJECT,
		MISSING_TREE,
		MISSING_TYPE_ENTRY,
		MISSING_TAG_ENTRY,
		BAD_DATE,
		BAD_EMAIL,
		BAD_TIMEZONE,
		MISSING_EMAIL,
		MISSING_SPACE_BEFORE_DATE,
		GITMODULES_BLOB,
		GITMODULES_LARGE,
		GITMODULES_NAME,
		GITMODULES_PARSE,
		GITMODULES_PATH,
		GITMODULES_SYMLINK,
		GITMODULES_URL,
		UNKNOWN_TYPE,

		WIN32_BAD_NAME,
		BAD_UTF8;

		public String getMessageId() {
			String n = name();
			StringBuilder r = new StringBuilder(n.length());
			for(int i = 0; i < n.length(); i++) {
				char c = n.charAt(i);
				if(c != '_') {
					r.append(StringUtils.toLowerCase(c));
				} else {
					r.append(n.charAt(++i));
				}
			}
			return r.toString();
		}
	}

	private final MutableObjectId tempId = new MutableObjectId();
	private final MutableInteger bufPtr = new MutableInteger();

	private EnumSet<ErrorType> errors = EnumSet.allOf(ErrorType.class);
	private ObjectIdSet skipList;
	private boolean allowInvalidPersonIdent;
	private boolean windows;
	private boolean macosx;

	private final List<GitmoduleEntry> gitsubmodules = new ArrayList<>();

	public ObjectChecker setSkipList(@Nullable ObjectIdSet objects) {
		skipList = objects;
		return this;
	}

	public ObjectChecker setIgnore(@Nullable Set<ErrorType> ids) {
		errors = EnumSet.allOf(ErrorType.class);
		if(ids != null) {
			errors.removeAll(ids);
		}
		return this;
	}

	public ObjectChecker setAllowInvalidPersonIdent(boolean allow) {
		allowInvalidPersonIdent = allow;
		return this;
	}

	public ObjectChecker setSafeForWindows(boolean win) {
		windows = win;
		return this;
	}

	public ObjectChecker setSafeForMacOS(boolean mac) {
		macosx = mac;
		return this;
	}

	public void check(int objType, byte[] raw)
			throws CorruptObjectException {
		check(idFor(objType, raw), objType, raw);
	}

	public void check(@Nullable AnyObjectId id, int objType, byte[] raw)
			throws CorruptObjectException {
		switch(objType) {
			case OBJ_COMMIT:
				checkCommit(id, raw);
				break;
			case OBJ_TAG:
				checkTag(id, raw);
				break;
			case OBJ_TREE:
				checkTree(id, raw);
				break;
			case OBJ_BLOB:
				BlobObjectChecker checker = newBlobObjectChecker();
				if(checker != null) {
					checker.update(raw, 0, raw.length);
					checker.endBlob(id);
				}
				break;
			default:
				report(UNKNOWN_TYPE, id, MessageFormat.format(
						JGitText.get().corruptObjectInvalidType2, objType));
		}
	}

	private boolean checkId(byte[] raw) {
		int p = bufPtr.value;
		try {
			tempId.fromString(raw, p);
		} catch(IllegalArgumentException e) {
			bufPtr.value = nextLF(raw, p);
			return false;
		}

		p += OBJECT_ID_STRING_LENGTH;
		if(raw[p] == '\n') {
			bufPtr.value = p + 1;
			return true;
		}
		bufPtr.value = nextLF(raw, p);
		return false;
	}

	private void checkPersonIdent(byte[] raw, @Nullable AnyObjectId id)
			throws CorruptObjectException {
		if(allowInvalidPersonIdent) {
			bufPtr.value = nextLF(raw, bufPtr.value);
			return;
		}

		final int emailB = nextLF(raw, bufPtr.value, '<');
		if(emailB == bufPtr.value || raw[emailB - 1] != '<') {
			report(MISSING_EMAIL, id, JGitText.get().corruptObjectMissingEmail);
			bufPtr.value = nextLF(raw, bufPtr.value);
			return;
		}

		final int emailE = nextLF(raw, emailB, '>');
		if(emailE == emailB || raw[emailE - 1] != '>') {
			report(BAD_EMAIL, id, JGitText.get().corruptObjectBadEmail);
			bufPtr.value = nextLF(raw, bufPtr.value);
			return;
		}
		if(emailE == raw.length || raw[emailE] != ' ') {
			report(MISSING_SPACE_BEFORE_DATE, id,
					JGitText.get().corruptObjectBadDate);
			bufPtr.value = nextLF(raw, bufPtr.value);
			return;
		}

		parseBase10(raw, emailE + 1, bufPtr);
		if(emailE + 1 == bufPtr.value || bufPtr.value == raw.length
				|| raw[bufPtr.value] != ' ') {
			report(BAD_DATE, id, JGitText.get().corruptObjectBadDate);
			bufPtr.value = nextLF(raw, bufPtr.value);
			return;
		}

		int p = bufPtr.value + 1;
		parseBase10(raw, p, bufPtr);

		p = bufPtr.value;
		if(raw[p] == '\n') {
			bufPtr.value = p + 1;
		} else {
			report(BAD_TIMEZONE, id, JGitText.get().corruptObjectBadTimezone);
			bufPtr.value = nextLF(raw, p);
		}
	}

	public void checkCommit(@Nullable AnyObjectId id, byte[] raw)
			throws CorruptObjectException {
		bufPtr.value = 0;

		if(!match(raw, tree)) {
			report(MISSING_TREE, id, JGitText.get().corruptObjectNotreeHeader);
		} else if(!checkId(raw)) {
			report(BAD_TREE_SHA1, id, JGitText.get().corruptObjectInvalidTree);
		}

		while(match(raw, parent)) {
			if(!checkId(raw)) {
				report(BAD_PARENT_SHA1, id,
						JGitText.get().corruptObjectInvalidParent);
			}
		}

		if(match(raw, author)) {
			checkPersonIdent(raw, id);
		} else {
			report(MISSING_AUTHOR, id, JGitText.get().corruptObjectNoAuthor);
		}

		if(match(raw, committer)) {
			checkPersonIdent(raw, id);
		} else {
			report(MISSING_COMMITTER, id,
					JGitText.get().corruptObjectNoCommitter);
		}
	}

	public void checkTag(@Nullable AnyObjectId id, byte[] raw)
			throws CorruptObjectException {
		bufPtr.value = 0;
		if(!match(raw, object)) {
			report(MISSING_OBJECT, id,
					JGitText.get().corruptObjectNoObjectHeader);
		} else if(!checkId(raw)) {
			report(BAD_OBJECT_SHA1, id,
					JGitText.get().corruptObjectInvalidObject);
		}

		if(!match(raw, type)) {
			report(MISSING_TYPE_ENTRY, id,
					JGitText.get().corruptObjectNoTypeHeader);
		}
		bufPtr.value = nextLF(raw, bufPtr.value);

		if(!match(raw, tag)) {
			report(MISSING_TAG_ENTRY, id,
					JGitText.get().corruptObjectNoTagHeader);
		}
		bufPtr.value = nextLF(raw, bufPtr.value);

		if(match(raw, tagger)) {
			checkPersonIdent(raw, id);
		}
	}

	private static boolean duplicateName(final byte[] raw,
										 final int thisNamePos, final int thisNameEnd) {
		final int sz = raw.length;
		int nextPtr = thisNameEnd + 1 + Constants.OBJECT_ID_LENGTH;
		for(; ; ) {
			int nextMode = 0;
			for(; ; ) {
				if(nextPtr >= sz)
					return false;
				final byte c = raw[nextPtr++];
				if(' ' == c)
					break;
				nextMode <<= 3;
				nextMode += c - '0';
			}

			final int nextNamePos = nextPtr;
			for(; ; ) {
				if(nextPtr == sz)
					return false;
				final byte c = raw[nextPtr++];
				if(c == 0)
					break;
			}
			if(nextNamePos + 1 == nextPtr)
				return false;

			int cmp = compareSameName(
					raw, thisNamePos, thisNameEnd,
					raw, nextNamePos, nextPtr - 1, nextMode);
			if(cmp < 0)
				return false;
			else if(cmp == 0)
				return true;

			nextPtr += Constants.OBJECT_ID_LENGTH;
		}
	}

	public void checkTree(byte[] raw) throws CorruptObjectException {
		checkTree(idFor(OBJ_TREE, raw), raw);
	}

	public void checkTree(@Nullable AnyObjectId id, byte[] raw)
			throws CorruptObjectException {
		final int sz = raw.length;
		int ptr = 0;
		int lastNameB = 0, lastNameE = 0, lastMode = 0;
		Set<String> normalized = windows || macosx
				? new HashSet<>()
				: null;

		while(ptr < sz) {
			int thisMode = 0;
			for(; ; ) {
				if(ptr == sz) {
					throw new CorruptObjectException(
							JGitText.get().corruptObjectTruncatedInMode);
				}
				final byte c = raw[ptr++];
				if(' ' == c)
					break;
				if(c < '0' || c > '7') {
					throw new CorruptObjectException(
							JGitText.get().corruptObjectInvalidModeChar);
				}
				if(thisMode == 0 && c == '0') {
					report(ZERO_PADDED_FILEMODE, id,
							JGitText.get().corruptObjectInvalidModeStartsZero);
				}
				thisMode <<= 3;
				thisMode += c - '0';
			}

			if(FileMode.fromBits(thisMode).getObjectType() == OBJ_BAD) {
				throw new CorruptObjectException(MessageFormat.format(
						JGitText.get().corruptObjectInvalidMode2, thisMode));
			}

			final int thisNameB = ptr;
			ptr = scanPathSegment(raw, ptr, sz, id);
			if(ptr == sz || raw[ptr] != 0) {
				throw new CorruptObjectException(
						JGitText.get().corruptObjectTruncatedInName);
			}
			checkPathSegment2(raw, thisNameB, ptr, id);
			if(normalized != null) {
				if(!normalized.add(normalize(raw, thisNameB, ptr))) {
					report(DUPLICATE_ENTRIES, id,
							JGitText.get().corruptObjectDuplicateEntryNames);
				}
			} else if(duplicateName(raw, thisNameB, ptr)) {
				report(DUPLICATE_ENTRIES, id,
						JGitText.get().corruptObjectDuplicateEntryNames);
			}

			if(lastNameB != 0) {
				int cmp = compare(
						raw, lastNameB, lastNameE, lastMode,
						raw, thisNameB, ptr, thisMode);
				if(cmp > 0) {
					report(TREE_NOT_SORTED, id,
							JGitText.get().corruptObjectIncorrectSorting);
				}
			}

			lastNameB = thisNameB;
			lastNameE = ptr;
			lastMode = thisMode;

			ptr += 1 + OBJECT_ID_LENGTH;
			if(ptr > sz) {
				throw new CorruptObjectException(
						JGitText.get().corruptObjectTruncatedInObjectId);
			}

			if(ObjectId.zeroId().compareTo(raw, ptr - OBJECT_ID_LENGTH) == 0) {
				report(NULL_SHA1, id, JGitText.get().corruptObjectZeroId);
			}

			if(id != null && isGitmodules(raw, lastNameB, lastNameE, id)) {
				ObjectId blob = ObjectId.fromRaw(raw, ptr - OBJECT_ID_LENGTH);
				gitsubmodules.add(new GitmoduleEntry(id, blob));
			}
		}
	}

	private int scanPathSegment(byte[] raw, int ptr, int end,
								@Nullable AnyObjectId id) throws CorruptObjectException {
		for(; ptr < end; ptr++) {
			byte c = raw[ptr];
			if(c == 0) {
				return ptr;
			}
			if(c == '/') {
				report(FULL_PATHNAME, id,
						JGitText.get().corruptObjectNameContainsSlash);
			}
			if(windows && isInvalidOnWindows(c)) {
				if(c > 31) {
					throw new CorruptObjectException(String.format(
							JGitText.get().corruptObjectNameContainsChar, c));
				}
				throw new CorruptObjectException(String.format(
						JGitText.get().corruptObjectNameContainsByte, c & 0xff));
			}
		}
		return ptr;
	}

	@Nullable
	private ObjectId idFor(int objType, byte[] raw) {
		if(skipList != null) {
			try(ObjectInserter.Formatter fmt = new ObjectInserter.Formatter()) {
				return fmt.idFor(objType, raw);
			}
		}
		return null;
	}

	private void report(@NonNull ErrorType err, @Nullable AnyObjectId id,
						String why) throws CorruptObjectException {
		if(errors.contains(err)
				&& (id == null || skipList == null || !skipList.contains(id))) {
			if(id != null) {
				throw new CorruptObjectException(err, id, why);
			}
			throw new CorruptObjectException(why);
		}
	}

	public void checkPath(String path) throws CorruptObjectException {
		byte[] buf = Constants.encode(path);
		checkPath(buf, 0, buf.length);
	}

	public void checkPath(byte[] raw, int ptr, int end)
			throws CorruptObjectException {
		int start = ptr;
		for(; ptr < end; ptr++) {
			if(raw[ptr] == '/') {
				checkPathSegment(raw, start, ptr);
				start = ptr + 1;
			}
		}
		checkPathSegment(raw, start, end);
	}

	public void checkPathSegment(byte[] raw, int ptr, int end)
			throws CorruptObjectException {
		int e = scanPathSegment(raw, ptr, end, null);
		if(e < end && raw[e] == 0)
			throw new CorruptObjectException(
					JGitText.get().corruptObjectNameContainsNullByte);
		checkPathSegment2(raw, ptr, end, null);
	}

	private void checkPathSegment2(byte[] raw, int ptr, int end,
								   @Nullable AnyObjectId id) throws CorruptObjectException {
		if(ptr == end) {
			report(EMPTY_NAME, id, JGitText.get().corruptObjectNameZeroLength);
			return;
		}

		if(raw[ptr] == '.') {
			switch(end - ptr) {
				case 1:
					report(HAS_DOT, id, JGitText.get().corruptObjectNameDot);
					break;
				case 2:
					if(raw[ptr + 1] == '.') {
						report(HAS_DOTDOT, id,
								JGitText.get().corruptObjectNameDotDot);
					}
					break;
				case 4:
					if(isGit(raw, ptr + 1)) {
						report(HAS_DOTGIT, id, String.format(
								JGitText.get().corruptObjectInvalidName,
								RawParseUtils.decode(raw, ptr, end)));
					}
					break;
				default:
					if(end - ptr > 4 && isNormalizedGit(raw, ptr + 1, end)) {
						report(HAS_DOTGIT, id, String.format(
								JGitText.get().corruptObjectInvalidName,
								RawParseUtils.decode(raw, ptr, end)));
					}
			}
		} else if(isGitTilde1(raw, ptr, end)) {
			report(HAS_DOTGIT, id, String.format(
					JGitText.get().corruptObjectInvalidName,
					RawParseUtils.decode(raw, ptr, end)));
		}
		if(macosx && isMacHFSGit(raw, ptr, end, id)) {
			report(HAS_DOTGIT, id, String.format(
					JGitText.get().corruptObjectInvalidNameIgnorableUnicode,
					RawParseUtils.decode(raw, ptr, end)));
		}

		if(windows) {
			if(raw[end - 1] == ' ' || raw[end - 1] == '.') {
				report(WIN32_BAD_NAME, id, String.format(
						JGitText.get().corruptObjectInvalidNameEnd, ((char) raw[end - 1])));
			}
			if(end - ptr >= 3) {
				checkNotWindowsDevice(raw, ptr, end, id);
			}
		}
	}

	private boolean isMacHFSPath(byte[] raw, int ptr, int end, byte[] path,
								 @Nullable AnyObjectId id) throws CorruptObjectException {
		boolean ignorable = false;
		int g = 0;
		while(ptr < end) {
			switch(raw[ptr]) {
				case (byte) 0xe2:
					if(!checkTruncatedIgnorableUTF8(raw, ptr, end, id)) {
						return false;
					}
					switch(raw[ptr + 1]) {
						case (byte) 0x80:
							switch(raw[ptr + 2]) {
								case (byte) 0x8c:
								case (byte) 0x8d:
								case (byte) 0x8e:
								case (byte) 0x8f:
								case (byte) 0xaa:
								case (byte) 0xab:
								case (byte) 0xac:
								case (byte) 0xad:
								case (byte) 0xae:
									ignorable = true;
									ptr += 3;
									continue;
								default:
									return false;
							}
						case (byte) 0x81:
							switch(raw[ptr + 2]) {
								case (byte) 0xaa:
								case (byte) 0xab:
								case (byte) 0xac:
								case (byte) 0xad:
								case (byte) 0xae:
								case (byte) 0xaf:
									ignorable = true;
									ptr += 3;
									continue;
								default:
									return false;
							}
						default:
							return false;
					}
				case (byte) 0xef:
					if(!checkTruncatedIgnorableUTF8(raw, ptr, end, id)) {
						return false;
					}

					if((raw[ptr + 1] == (byte) 0xbb)
							&& (raw[ptr + 2] == (byte) 0xbf)) {
						ignorable = true;
						ptr += 3;
						continue;
					}
					return false;
				default:
					if(g == path.length) {
						return false;
					}
					if(toLower(raw[ptr++]) != path[g++]) {
						return false;
					}
			}
		}
		return g == path.length && ignorable;
	}

	private boolean isMacHFSGit(byte[] raw, int ptr, int end,
								@Nullable AnyObjectId id) throws CorruptObjectException {
		byte[] git = new byte[] {'.', 'g', 'i', 't'};
		return isMacHFSPath(raw, ptr, end, git, id);
	}

	private boolean isMacHFSGitmodules(byte[] raw, int ptr, int end,
									   @Nullable AnyObjectId id) throws CorruptObjectException {
		return isMacHFSPath(raw, ptr, end, dotGitmodules, id);
	}

	private boolean checkTruncatedIgnorableUTF8(byte[] raw, int ptr, int end,
												@Nullable AnyObjectId id) throws CorruptObjectException {
		if((ptr + 2) >= end) {
			report(BAD_UTF8, id, MessageFormat.format(
					JGitText.get().corruptObjectInvalidNameInvalidUtf8,
					toHexString(raw, ptr, end)));
			return false;
		}
		return true;
	}

	private static String toHexString(byte[] raw, int ptr, int end) {
		StringBuilder b = new StringBuilder("0x");
		for(int i = ptr; i < end; i++)
			b.append(String.format("%02x", raw[i]));
		return b.toString();
	}

	private void checkNotWindowsDevice(byte[] raw, int ptr, int end,
									   @Nullable AnyObjectId id) throws CorruptObjectException {
		switch(toLower(raw[ptr])) {
			case 'a':
				if(end - ptr >= 3
						&& toLower(raw[ptr + 1]) == 'u'
						&& toLower(raw[ptr + 2]) == 'x'
						&& (end - ptr == 3 || raw[ptr + 3] == '.')) {
					report(WIN32_BAD_NAME, id,
							JGitText.get().corruptObjectInvalidNameAux);
				}
				break;

			case 'c':
				if(end - ptr >= 3
						&& toLower(raw[ptr + 2]) == 'n'
						&& toLower(raw[ptr + 1]) == 'o'
						&& (end - ptr == 3 || raw[ptr + 3] == '.')) {
					report(WIN32_BAD_NAME, id,
							JGitText.get().corruptObjectInvalidNameCon);
				}
				if(end - ptr >= 4
						&& toLower(raw[ptr + 2]) == 'm'
						&& toLower(raw[ptr + 1]) == 'o'
						&& isPositiveDigit(raw[ptr + 3])
						&& (end - ptr == 4 || raw[ptr + 4] == '.')) {
					report(WIN32_BAD_NAME, id, String.format(
							JGitText.get().corruptObjectInvalidNameCom, ((char) raw[ptr + 3])));
				}
				break;

			case 'l':
				if(end - ptr >= 4
						&& toLower(raw[ptr + 1]) == 'p'
						&& toLower(raw[ptr + 2]) == 't'
						&& isPositiveDigit(raw[ptr + 3])
						&& (end - ptr == 4 || raw[ptr + 4] == '.')) {
					report(WIN32_BAD_NAME, id, String.format(
							JGitText.get().corruptObjectInvalidNameLpt, ((char) raw[ptr + 3])));
				}
				break;

			case 'n':
				if(end - ptr >= 3
						&& toLower(raw[ptr + 1]) == 'u'
						&& toLower(raw[ptr + 2]) == 'l'
						&& (end - ptr == 3 || raw[ptr + 3] == '.')) {
					report(WIN32_BAD_NAME, id,
							JGitText.get().corruptObjectInvalidNameNul);
				}
				break;

			case 'p':
				if(end - ptr >= 3
						&& toLower(raw[ptr + 1]) == 'r'
						&& toLower(raw[ptr + 2]) == 'n'
						&& (end - ptr == 3 || raw[ptr + 3] == '.')) {
					report(WIN32_BAD_NAME, id,
							JGitText.get().corruptObjectInvalidNamePrn);
				}
				break;
		}
	}

	private static boolean isInvalidOnWindows(byte c) {
		switch(c) {
			case '"':
			case '*':
			case ':':
			case '<':
			case '>':
			case '?':
			case '\\':
			case '|':
				return true;
		}
		return 1 <= c && c <= 31;
	}

	private static boolean isGit(byte[] buf, int p) {
		return toLower(buf[p]) == 'g'
				&& toLower(buf[p + 1]) == 'i'
				&& toLower(buf[p + 2]) == 't';
	}

	private boolean isGitmodules(byte[] buf, int start, int end, @Nullable AnyObjectId id)
			throws CorruptObjectException {
		if(end - start < 8) {
			return false;
		}
		return (end - start == dotGitmodules.length
				&& RawParseUtils.match(buf, start, dotGitmodules) != -1)
				|| (macosx && isMacHFSGitmodules(buf, start, end, id))
				|| (windows && isNTFSGitmodules(buf, start, end));
	}

	private boolean matchLowerCase(byte[] b, int ptr, byte[] src) {
		if(ptr + src.length > b.length) {
			return false;
		}
		for(int i = 0; i < src.length; i++, ptr++) {
			if(toLower(b[ptr]) != src[i]) {
				return false;
			}
		}
		return true;
	}

	private boolean isNTFSGitmodules(byte[] buf, int start, int end) {
		if(end - start == 11) {
			return matchLowerCase(buf, start, dotGitmodules);
		}

		if(end - start != 8) {
			return false;
		}

		byte[] gitmod = new byte[] {'g', 'i', 't', 'm', 'o', 'd', '~'};
		if(matchLowerCase(buf, start, gitmod)) {
			start += 6;
		} else {
			byte[] gi7eba = new byte[] {'g', 'i', '7', 'e', 'b', 'a'};
			for(int i = 0; i < gi7eba.length; i++, start++) {
				byte c = (byte) toLower(buf[start]);
				if(c == '~') {
					break;
				}
				if(c != gi7eba[i]) {
					return false;
				}
			}
		}

		if(end - start < 2) {
			return false;
		}
		if(buf[start] != '~') {
			return false;
		}
		start++;
		if(buf[start] < '1' || buf[start] > '9') {
			return false;
		}
		start++;
		for(; start != end; start++) {
			if(buf[start] < '0' || buf[start] > '9') {
				return false;
			}
		}
		return true;
	}

	private static boolean isGitTilde1(byte[] buf, int p, int end) {
		if(end - p != 5)
			return false;
		return toLower(buf[p]) == 'g' && toLower(buf[p + 1]) == 'i'
				&& toLower(buf[p + 2]) == 't' && buf[p + 3] == '~'
				&& buf[p + 4] == '1';
	}

	private static boolean isNormalizedGit(byte[] raw, int ptr, int end) {
		if(isGit(raw, ptr)) {
			int dots = 0;
			boolean space = false;
			int p = end - 1;
			for(; (ptr + 2) < p; p--) {
				if(raw[p] == '.')
					dots++;
				else if(raw[p] == ' ')
					space = true;
				else
					break;
			}
			return p == ptr + 2 && (dots == 1 || space);
		}
		return false;
	}

	private boolean match(byte[] b, byte[] src) {
		int r = RawParseUtils.match(b, bufPtr.value, src);
		if(r < 0) {
			return false;
		}
		bufPtr.value = r;
		return true;
	}

	private static char toLower(byte b) {
		if('A' <= b && b <= 'Z')
			return (char) (b + ('a' - 'A'));
		return (char) b;
	}

	private static boolean isPositiveDigit(byte b) {
		return '1' <= b && b <= '9';
	}

	@Nullable
	public BlobObjectChecker newBlobObjectChecker() {
		return null;
	}

	private String normalize(byte[] raw, int ptr, int end) {
		String n = RawParseUtils.decode(raw, ptr, end).toLowerCase(Locale.US);
		return macosx ? Normalizer.normalize(n, Normalizer.Form.NFC) : n;
	}

	public List<GitmoduleEntry> getGitsubmodules() {
		return gitsubmodules;
	}

	public void reset() {
		gitsubmodules.clear();
	}
}
