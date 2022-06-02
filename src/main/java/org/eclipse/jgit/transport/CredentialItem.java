/*
 * Copyright (C) 2010, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import java.util.Arrays;

import org.eclipse.jgit.internal.JGitText;

public abstract class CredentialItem {

	public CredentialItem() {
	}

	public abstract void clear();

	public static class StringType extends CredentialItem {
		private String value;

		public StringType(String promptText) {
			super();
		}

		@Override
		public void clear() {
			value = null;
		}

		public String getValue() {
			return value;
		}

		public void setValue(String newValue) {
			value = newValue;
		}
	}

	public static class CharArrayType extends CredentialItem {
		private char[] value;

		public CharArrayType(String promptText) {
			super();
		}

		@Override
		public void clear() {
			if(value != null) {
				Arrays.fill(value, (char) 0);
				value = null;
			}
		}

		public char[] getValue() {
			return value;
		}

		public void setValue(char[] newValue) {
			clear();

			if(newValue != null) {
				value = new char[newValue.length];
				System.arraycopy(newValue, 0, value, 0, newValue.length);
			}
		}

	}

	public static class YesNoType extends CredentialItem {
		private boolean value;

		public YesNoType(String promptText) {
			super();
		}

		@Override
		public void clear() {
			value = false;
		}

		public boolean getValue() {
			return value;
		}

		public void setValue(boolean newValue) {
			value = newValue;
		}
	}

	public static class InformationalMessage extends CredentialItem {

		public InformationalMessage(String messageText) {
			super();
		}

		@Override
		public void clear() {
		}
	}

	public static class Username extends StringType {

		public Username() {
			super(JGitText.get().credentialUsername);
		}
	}

	public static class Password extends CharArrayType {

		public Password() {
			super(JGitText.get().credentialPassword);
		}
	}
}
