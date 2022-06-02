/*
 * Copyright (C) 2011, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import org.eclipse.jgit.errors.NotSupportedException;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.Repository;

public abstract class TransportProtocol {
	public enum URIishField {
		USER,
		PASS,
		HOST,
		PORT,
		PATH,
	}

	public Set<String> getSchemes() {
		return Collections.emptySet();
	}

	public Set<URIishField> getRequiredFields() {
		return Collections.unmodifiableSet(EnumSet.of(URIishField.PATH));
	}

	public Set<URIishField> getOptionalFields() {
		return Collections.emptySet();
	}

	public boolean canHandle(URIish uri, Repository local, String remoteName) {
		if(!getSchemes().isEmpty() && !getSchemes().contains(uri.getScheme()))
			return false;

		for(URIishField field : getRequiredFields()) {
			switch(field) {
				case USER:
					if(uri.getUser() == null || uri.getUser().length() == 0)
						return false;
					break;

				case PASS:
					if(uri.getPass() == null || uri.getPass().length() == 0)
						return false;
					break;

				case HOST:
					if(uri.getHost() == null || uri.getHost().length() == 0)
						return false;
					break;

				case PORT:
					if(uri.getPort() <= 0)
						return false;
					break;

				case PATH:
					if(uri.getPath() == null || uri.getPath().length() == 0)
						return false;
					break;

				default:
					return false;
			}
		}

		Set<URIishField> canHave = EnumSet.copyOf(getRequiredFields());
		canHave.addAll(getOptionalFields());

		if(uri.getUser() != null && !canHave.contains(URIishField.USER))
			return false;
		if(uri.getPass() != null && !canHave.contains(URIishField.PASS))
			return false;
		if(uri.getHost() != null && !canHave.contains(URIishField.HOST))
			return false;
		if(uri.getPort() > 0 && !canHave.contains(URIishField.PORT))
			return false;
		return uri.getPath() == null || canHave.contains(URIishField.PATH);
	}

	public abstract Transport open(URIish uri, Repository local,
								   String remoteName)
			throws NotSupportedException, TransportException;

	public Transport open(URIish uri)
			throws NotSupportedException, TransportException {
		throw new NotSupportedException(JGitText
				.get().transportNeedsRepository);
	}
}
