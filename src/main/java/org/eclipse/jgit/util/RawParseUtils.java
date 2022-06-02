/*
 * Copyright (C) 2008-2009, Google Inc.
 * Copyright (C) 2006-2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.util;

import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.errors.BinaryBlobException;
import org.eclipse.jgit.lib.PersonIdent;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.jgit.lib.ObjectChecker.*;

public final class RawParseUtils {

	@Deprecated
	public static final Charset UTF8_CHARSET = UTF_8;

	private static final byte[] digits10;

	private static final byte[] digits16;

	private static final byte[] footerLineKeyChars;

	private static final Map<String, Charset> encodingAliases;

	static {
		encodingAliases = new HashMap<>();
		encodingAliases.put("latin-1", ISO_8859_1);
		encodingAliases.put("iso-latin-1", ISO_8859_1);

		digits10 = new byte['9' + 1];
		Arrays.fill(digits10, (byte) -1);
		for(char i = '0'; i <= '9'; i++)
			digits10[i] = (byte) (i - '0');

		digits16 = new byte['f' + 1];
		Arrays.fill(digits16, (byte) -1);
		for(char i = '0'; i <= '9'; i++)
			digits16[i] = (byte) (i - '0');
		for(char i = 'a'; i <= 'f'; i++)
			digits16[i] = (byte) ((i - 'a') + 10);
		for(char i = 'A'; i <= 'F'; i++)
			digits16[i] = (byte) ((i - 'A') + 10);

		footerLineKeyChars = new byte['z' + 1];
		footerLineKeyChars['-'] = 1;
		for(char i = '0'; i <= '9'; i++)
			footerLineKeyChars[i] = 1;
		for(char i = 'A'; i <= 'Z'; i++)
			footerLineKeyChars[i] = 1;
		for(char i = 'a'; i <= 'z'; i++)
			footerLineKeyChars[i] = 1;
	}

	public static int match(byte[] b, int ptr, byte[] src) {
		if(ptr + src.length > b.length)
			return -1;
		for(int i = 0; i < src.length; i++, ptr++)
			if(b[ptr] != src[i])
				return -1;
		return ptr;
	}

	private static final byte[] base10byte = {'0', '1', '2', '3', '4', '5',
			'6', '7', '8', '9'};

	public static int formatBase10(final byte[] b, int o, int value) {
		if(value == 0) {
			b[--o] = '0';
			return o;
		}
		final boolean isneg = value < 0;
		if(isneg)
			value = -value;
		while(value != 0) {
			b[--o] = base10byte[value % 10];
			value /= 10;
		}
		if(isneg)
			b[--o] = '-';
		return o;
	}

	public static int parseBase10(final byte[] b, int ptr,
								  final MutableInteger ptrResult) {
		int r = 0;
		int sign = 0;
		try {
			final int sz = b.length;
			while(ptr < sz && b[ptr] == ' ')
				ptr++;
			if(ptr >= sz)
				return 0;

			switch(b[ptr]) {
				case '-':
					sign = -1;
					ptr++;
					break;
				case '+':
					ptr++;
					break;
			}

			while(ptr < sz) {
				final byte v = digits10[b[ptr]];
				if(v < 0)
					break;
				r = (r * 10) + v;
				ptr++;
			}
		} catch(ArrayIndexOutOfBoundsException ignored) {
		}
		if(ptrResult != null)
			ptrResult.value = ptr;
		return sign < 0 ? -r : r;
	}

	public static long parseLongBase10(final byte[] b, int ptr,
									   final MutableInteger ptrResult) {
		long r = 0;
		int sign = 0;
		try {
			final int sz = b.length;
			while(ptr < sz && b[ptr] == ' ')
				ptr++;
			if(ptr >= sz)
				return 0;

			switch(b[ptr]) {
				case '-':
					sign = -1;
					ptr++;
					break;
				case '+':
					ptr++;
					break;
			}

			while(ptr < sz) {
				final byte v = digits10[b[ptr]];
				if(v < 0)
					break;
				r = (r * 10) + v;
				ptr++;
			}
		} catch(ArrayIndexOutOfBoundsException ignored) {
		}
		if(ptrResult != null)
			ptrResult.value = ptr;
		return sign < 0 ? -r : r;
	}

	public static int parseHexInt16(final byte[] bs, final int p) {
		int r = digits16[bs[p]] << 4;

		r |= digits16[bs[p + 1]];
		r <<= 4;

		r |= digits16[bs[p + 2]];
		r <<= 4;

		r |= digits16[bs[p + 3]];
		if(r < 0)
			throw new ArrayIndexOutOfBoundsException();
		return r;
	}

	public static int parseHexInt32(final byte[] bs, final int p) {
		int r = digits16[bs[p]] << 4;

		r |= digits16[bs[p + 1]];
		r <<= 4;

		r |= digits16[bs[p + 2]];
		r <<= 4;

		r |= digits16[bs[p + 3]];
		r <<= 4;

		r |= digits16[bs[p + 4]];
		r <<= 4;

		r |= digits16[bs[p + 5]];
		r <<= 4;

		r |= digits16[bs[p + 6]];

		final int last = digits16[bs[p + 7]];
		if(r < 0 || last < 0)
			throw new ArrayIndexOutOfBoundsException();
		return (r << 4) | last;
	}

	public static int parseHexInt4(final byte digit) {
		final byte r = digits16[digit];
		if(r < 0)
			throw new ArrayIndexOutOfBoundsException();
		return r;
	}

	public static int parseTimeZoneOffset(byte[] b, int ptr) {
		return parseTimeZoneOffset(b, ptr, null);
	}

	public static int parseTimeZoneOffset(final byte[] b, int ptr,
										  MutableInteger ptrResult) {
		final int v = parseBase10(b, ptr, ptrResult);
		final int tzMins = v % 100;
		final int tzHours = v / 100;
		return tzHours * 60 + tzMins;
	}

	public static int next(byte[] b, int ptr, char chrA) {
		final int sz = b.length;
		while(ptr < sz) {
			if(b[ptr++] == chrA)
				return ptr;
		}
		return ptr;
	}

	public static int nextLF(byte[] b, int ptr) {
		return next(b, ptr, '\n');
	}

	public static int nextLF(byte[] b, int ptr, char chrA) {
		final int sz = b.length;
		while(ptr < sz) {
			final byte c = b[ptr++];
			if(c == chrA || c == '\n')
				return ptr;
		}
		return ptr;
	}

	public static int prev(byte[] b, int ptr, char chrA) {
		if(ptr == b.length)
			--ptr;
		while(ptr >= 0) {
			if(b[ptr--] == chrA)
				return ptr;
		}
		return ptr;
	}

	public static int prevLF(byte[] b, int ptr) {
		return prev(b, ptr, '\n');
	}

	public static IntList lineMap(byte[] buf, int ptr, int end) {
		IntList map = new IntList((end - ptr) / 36);
		map.fillTo(1, Integer.MIN_VALUE);
		for(; ptr < end; ptr = nextLF(buf, ptr)) {
			map.add(ptr);
		}
		map.add(end);
		return map;
	}

	public static IntList lineMapOrBinary(byte[] buf, int ptr, int end)
			throws BinaryBlobException {
		IntList map = new IntList((end - ptr) / 36);
		map.add(Integer.MIN_VALUE);
		byte last = '\n';
		for(; ptr < end; ptr++) {
			if(last == '\n') {
				map.add(ptr);
			}
			byte curr = buf[ptr];
			if(RawText.isBinary(curr, last)) {
				throw new BinaryBlobException();
			}
			last = curr;
		}
		if(last == '\r') {
			throw new BinaryBlobException();
		}
		map.add(end);
		return map;
	}

	public static int author(byte[] b, int ptr) {
		final int sz = b.length;
		if(ptr == 0)
			ptr += 46;
		while(ptr < sz && b[ptr] == 'p')
			ptr += 48;
		return match(b, ptr, author);
	}

	public static int committer(byte[] b, int ptr) {
		final int sz = b.length;
		if(ptr == 0)
			ptr += 46;
		while(ptr < sz && b[ptr] == 'p')
			ptr += 48;
		if(ptr < sz && b[ptr] == 'a')
			ptr = nextLF(b, ptr);
		return match(b, ptr, committer);
	}

	public static int tagger(byte[] b, int ptr) {
		final int sz = b.length;
		if(ptr == 0)
			ptr += 48;
		while(ptr < sz) {
			if(b[ptr] == '\n')
				return -1;
			final int m = match(b, ptr, tagger);
			if(m >= 0)
				return m;
			ptr = nextLF(b, ptr);
		}
		return -1;
	}

	public static int encoding(byte[] b, int ptr) {
		final int sz = b.length;
		while(ptr < sz) {
			if(b[ptr] == '\n')
				return -1;
			if(b[ptr] == 'e')
				break;
			ptr = nextLF(b, ptr);
		}
		return match(b, ptr, encoding);
	}

	@Nullable
	public static String parseEncodingName(byte[] b) {
		int enc = encoding(b, 0);
		if(enc < 0) {
			return null;
		}
		int lf = nextLF(b, enc);
		return decode(UTF_8, b, enc, lf - 1);
	}

	public static Charset parseEncoding(byte[] b) {
		String enc = parseEncodingName(b);
		if(enc == null) {
			return UTF_8;
		}

		String name = enc.trim();
		try {
			return Charset.forName(name);
		} catch(IllegalCharsetNameException
				| UnsupportedCharsetException badName) {
			Charset aliased = charsetForAlias(name);
			if(aliased != null) {
				return aliased;
			}
			throw badName;
		}
	}

	public static PersonIdent parsePersonIdent(byte[] raw, int nameB) {
		Charset cs;
		try {
			cs = parseEncoding(raw);
		} catch(IllegalCharsetNameException | UnsupportedCharsetException e) {
			cs = UTF_8;
		}

		final int emailB = nextLF(raw, nameB, '<');
		final int emailE = nextLF(raw, emailB, '>');
		if(emailB >= raw.length || raw[emailB] == '\n' ||
				(emailE >= raw.length - 1 && raw[emailE - 1] != '>'))
			return null;

		final int nameEnd = emailB - 2 >= nameB && raw[emailB - 2] == ' ' ?
				emailB - 2 : emailB - 1;
		final String name = decode(cs, raw, nameB, nameEnd);
		final String email = decode(cs, raw, emailB, emailE - 1);

		final int tzBegin = lastIndexOfTrim(raw, ' ',
				nextLF(raw, emailE - 1) - 2) + 1;
		if(tzBegin <= emailE)
			return new PersonIdent(name, email, 0, 0);

		final int whenBegin = Math.max(emailE,
				lastIndexOfTrim(raw, ' ', tzBegin - 1) + 1);
		if(whenBegin >= tzBegin - 1)
			return new PersonIdent(name, email, 0, 0);

		final long when = parseLongBase10(raw, whenBegin, null);
		final int tz = parseTimeZoneOffset(raw, tzBegin);
		return new PersonIdent(name, email, when * 1000L, tz);
	}

	public static PersonIdent parsePersonIdentOnly(final byte[] raw,
												   final int nameB) {
		int stop = nextLF(raw, nameB);
		int emailB = nextLF(raw, nameB, '<');
		int emailE = nextLF(raw, emailB, '>');
		final String name;
		final String email;
		if(emailE < stop) {
			email = decode(raw, emailB, emailE - 1);
		} else {
			email = "invalid";
		}
		if(emailB < stop)
			name = decode(raw, nameB, emailB - 2);
		else
			name = decode(raw, nameB, stop);

		final MutableInteger ptrout = new MutableInteger();
		long when;
		int tz;
		if(emailE < stop) {
			when = parseLongBase10(raw, emailE + 1, ptrout);
			tz = parseTimeZoneOffset(raw, ptrout.value);
		} else {
			when = 0;
			tz = 0;
		}
		return new PersonIdent(name, email, when * 1000L, tz);
	}

	public static String decode(byte[] buffer) {
		return decode(buffer, 0, buffer.length);
	}

	public static String decode(final byte[] buffer, final int start,
								final int end) {
		return decode(UTF_8, buffer, start, end);
	}

	public static String decode(Charset cs, byte[] buffer) {
		return decode(cs, buffer, 0, buffer.length);
	}

	public static String decode(final Charset cs, final byte[] buffer,
								final int start, final int end) {
		try {
			return decodeNoFallback(cs, buffer, start, end);
		} catch(CharacterCodingException e) {
			// Fall back to an ISO-8859-1 style encoding. At least all of
			// the bytes will be present in the output.
			//
			return extractBinaryString(buffer, start, end);
		}
	}

	public static String decodeNoFallback(final Charset cs,
										  final byte[] buffer, final int start, final int end)
			throws CharacterCodingException {
		ByteBuffer b = ByteBuffer.wrap(buffer, start, end - start);
		b.mark();

		try {
			return decode(b, UTF_8);
		} catch(CharacterCodingException e) {
			b.reset();
		}

		if(!cs.equals(UTF_8)) {
			try {
				return decode(b, cs);
			} catch(CharacterCodingException e) {
				b.reset();
			}
		}

		Charset defcs = SystemReader.getInstance().getDefaultCharset();
		if(!defcs.equals(cs) && !defcs.equals(UTF_8)) {
			try {
				return decode(b, defcs);
			} catch(CharacterCodingException e) {
				b.reset();
			}
		}

		throw new CharacterCodingException();
	}

	public static String extractBinaryString(final byte[] buffer,
											 final int start, final int end) {
		final StringBuilder r = new StringBuilder(end - start);
		for(int i = start; i < end; i++)
			r.append((char) (buffer[i] & 0xff));
		return r.toString();
	}

	private static String decode(ByteBuffer b, Charset charset)
			throws CharacterCodingException {
		final CharsetDecoder d = charset.newDecoder();
		d.onMalformedInput(CodingErrorAction.REPORT);
		d.onUnmappableCharacter(CodingErrorAction.REPORT);
		return d.decode(b).toString();
	}

	public static int commitMessage(byte[] b, int ptr) {
		final int sz = b.length;
		if(ptr == 0)
			ptr += 46;
		while(ptr < sz && b[ptr] == 'p')
			ptr += 48;

		return tagMessage(b, ptr);
	}

	public static int tagMessage(byte[] b, int ptr) {
		final int sz = b.length;
		if(ptr == 0)
			ptr += 48;
		while(ptr < sz && b[ptr] != '\n')
			ptr = nextLF(b, ptr);
		if(ptr < sz && b[ptr] == '\n')
			return ptr + 1;
		return -1;
	}

	public static int endOfParagraph(byte[] b, int start) {
		int ptr = start;
		final int sz = b.length;
		while(ptr < sz && (b[ptr] != '\n' && b[ptr] != '\r'))
			ptr = nextLF(b, ptr);
		if(ptr > start && b[ptr - 1] == '\n')
			ptr--;
		if(ptr > start && b[ptr - 1] == '\r')
			ptr--;
		return ptr;
	}

	public static int lastIndexOfTrim(byte[] raw, char ch, int pos) {
		while(pos >= 0 && raw[pos] == ' ')
			pos--;

		while(pos >= 0 && raw[pos] != ch)
			pos--;

		return pos;
	}

	private static Charset charsetForAlias(String name) {
		return encodingAliases.get(StringUtils.toLowerCase(name));
	}
}
