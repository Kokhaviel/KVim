/*
 * Copyright (C) 2020, Thomas Wolf <thomas.wolf@paranor.ch> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.lib;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Objects;

import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.util.References;

public abstract class ObjectBuilder {

	private static final byte[] hencoding = Constants.encodeASCII("encoding");

	private PersonIdent author;
	private GpgSignature gpgSignature;
	private String message;
	private Charset encoding = StandardCharsets.UTF_8;

	protected PersonIdent getAuthor() {
		return author;
	}

	protected void setAuthor(PersonIdent newAuthor) {
		author = Objects.requireNonNull(newAuthor);
	}

	@Nullable
	public GpgSignature getGpgSignature() {
		return gpgSignature;
	}

	@Nullable
	public String getMessage() {
		return message;
	}

	public void setMessage(@Nullable String message) {
		this.message = message;
	}

	@NonNull
	public Charset getEncoding() {
		return encoding;
	}

	public void setEncoding(@NonNull Charset encoding) {
		this.encoding = encoding;
	}

	@NonNull
	public abstract byte[] build() throws UnsupportedEncodingException;

	static void writeMultiLineHeader(@NonNull String in,
									 @NonNull OutputStream out)
			throws IOException, IllegalArgumentException {
		int length = in.length();
		for(int i = 0; i < length; ++i) {
			char ch = in.charAt(i);
			switch(ch) {
				case '\r':
					if(i + 1 < length && in.charAt(i + 1) == '\n') {
						++i;
					}
					if(i + 1 < length) {
						out.write('\n');
						out.write(' ');
					}
					break;
				case '\n':
					if(i + 1 < length) {
						out.write('\n');
						out.write(' ');
					}
					break;
				default:
					if(ch > 127)
						throw new IllegalArgumentException(MessageFormat
								.format(JGitText.get().notASCIIString, in));
					out.write(ch);
					break;
			}
		}
	}

	static void writeEncoding(@NonNull Charset encoding,
							  @NonNull OutputStream out) throws IOException {
		if(!References.isSameObject(encoding, UTF_8)) {
			out.write(hencoding);
			out.write(' ');
			out.write(Constants.encodeASCII(encoding.name()));
			out.write('\n');
		}
	}
}
