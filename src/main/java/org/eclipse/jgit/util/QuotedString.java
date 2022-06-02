/*
 * Copyright (C) 2008, 2019 Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.util;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.Arrays;

import org.eclipse.jgit.lib.Constants;

public abstract class QuotedString {

	public static final GitPathStyle GIT_PATH = new GitPathStyle(true);

	public static final QuotedString GIT_PATH_MINIMAL = new GitPathStyle(false);

	public static final BourneStyle BOURNE = new BourneStyle();

	public abstract String quote(String in);

	public abstract String dequote(byte[] in, int offset, int end);

	public static class BourneStyle extends QuotedString {
		@Override
		public String quote(String in) {
			final StringBuilder r = new StringBuilder();
			r.append('\'');
			int start = 0, i = 0;
			for(; i < in.length(); i++) {
				switch(in.charAt(i)) {
					case '\'':
					case '!':
						r.append(in, start, i);
						r.append('\'');
						r.append('\\');
						r.append(in.charAt(i));
						r.append('\'');
						start = i + 1;
						break;
				}
			}
			r.append(in, start, i);
			r.append('\'');
			return r.toString();
		}

		@Override
		public String dequote(byte[] in, int ip, int ie) {
			boolean inquote = false;
			final byte[] r = new byte[ie - ip];
			int rPtr = 0;
			while(ip < ie) {
				final byte b = in[ip++];
				switch(b) {
					case '\'':
						inquote = !inquote;
						continue;
					case '\\':
						if(inquote || ip == ie)
							r[rPtr++] = b;
						else
							r[rPtr++] = in[ip++];
						continue;
					default:
						r[rPtr++] = b;
				}
			}
			return RawParseUtils.decode(UTF_8, r, 0, rPtr);
		}
	}

	public static final class GitPathStyle extends QuotedString {
		private static final byte[] quote;

		static {
			quote = new byte[128];
			Arrays.fill(quote, (byte) -1);

			for(int i = '0'; i <= '9'; i++)
				quote[i] = 0;
			for(int i = 'a'; i <= 'z'; i++)
				quote[i] = 0;
			for(int i = 'A'; i <= 'Z'; i++)
				quote[i] = 0;
			quote[' '] = 0;
			quote['$'] = 0;
			quote['%'] = 0;
			quote['&'] = 0;
			quote['*'] = 0;
			quote['+'] = 0;
			quote[','] = 0;
			quote['-'] = 0;
			quote['.'] = 0;
			quote['/'] = 0;
			quote[':'] = 0;
			quote[';'] = 0;
			quote['='] = 0;
			quote['?'] = 0;
			quote['@'] = 0;
			quote['_'] = 0;
			quote['^'] = 0;
			quote['|'] = 0;
			quote['~'] = 0;

			quote['\u0007'] = 'a';
			quote['\b'] = 'b';
			quote['\f'] = 'f';
			quote['\n'] = 'n';
			quote['\r'] = 'r';
			quote['\t'] = 't';
			quote['\u000B'] = 'v';
			quote['\\'] = '\\';
			quote['"'] = '"';
		}

		private final boolean quoteHigh;

		@Override
		public String quote(String instr) {
			if(instr.isEmpty()) {
				return "\"\"";
			}
			boolean reuse = true;
			final byte[] in = Constants.encode(instr);
			final byte[] out = new byte[4 * in.length + 2];
			int o = 0;
			out[o++] = '"';
			for(byte element : in) {
				final int c = element & 0xff;
				if(c < quote.length) {
					final byte style = quote[c];
					if(style == 0) {
						out[o++] = (byte) c;
						continue;
					}
					if(style > 0) {
						reuse = false;
						out[o++] = '\\';
						out[o++] = style;
						continue;
					}
				} else if(!quoteHigh) {
					out[o++] = (byte) c;
					continue;
				}

				reuse = false;
				out[o++] = '\\';
				out[o++] = (byte) (((c >> 6) & 3) + '0');
				out[o++] = (byte) (((c >> 3) & 7) + '0');
				out[o++] = (byte) (((c) & 7) + '0');
			}
			if(reuse) {
				return instr;
			}
			out[o++] = '"';
			return new String(out, 0, o, UTF_8);
		}

		@Override
		public String dequote(byte[] in, int inPtr, int inEnd) {
			if(2 <= inEnd - inPtr && in[inPtr] == '"' && in[inEnd - 1] == '"')
				return dq(in, inPtr + 1, inEnd - 1);
			return RawParseUtils.decode(UTF_8, in, inPtr, inEnd);
		}

		private static String dq(byte[] in, int inPtr, int inEnd) {
			final byte[] r = new byte[inEnd - inPtr];
			int rPtr = 0;
			while(inPtr < inEnd) {
				final byte b = in[inPtr++];
				if(b != '\\') {
					r[rPtr++] = b;
					continue;
				}

				if(inPtr == inEnd) {
					r[rPtr++] = '\\';
					break;
				}

				switch(in[inPtr++]) {
					case 'a':
						r[rPtr++] = 0x07;
						continue;
					case 'b':
						r[rPtr++] = '\b';
						continue;
					case 'f':
						r[rPtr++] = '\f';
						continue;
					case 'n':
						r[rPtr++] = '\n';
						continue;
					case 'r':
						r[rPtr++] = '\r';
						continue;
					case 't':
						r[rPtr++] = '\t';
						continue;
					case 'v':
						r[rPtr++] = 0x0B;
						continue;

					case '\\':
					case '"':
						r[rPtr++] = in[inPtr - 1];
						continue;

					case '0':
					case '1':
					case '2':
					case '3': {
						int cp = in[inPtr - 1] - '0';
						for(int n = 1; n < 3 && inPtr < inEnd; n++) {
							final byte c = in[inPtr];
							if('0' <= c && c <= '7') {
								cp <<= 3;
								cp |= c - '0';
								inPtr++;
							} else {
								break;
							}
						}
						r[rPtr++] = (byte) cp;
						continue;
					}

					default:
						r[rPtr++] = '\\';
						r[rPtr++] = in[inPtr - 1];
				}
			}

			return RawParseUtils.decode(UTF_8, r, 0, rPtr);
		}

		private GitPathStyle(boolean doQuote) {
			quoteHigh = doQuote;
		}
	}
}
