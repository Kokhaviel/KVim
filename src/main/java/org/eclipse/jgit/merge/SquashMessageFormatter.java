/*
 * Copyright (C) 2012, IBM Corporation and others. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.merge;

import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.util.GitDateFormatter;
import org.eclipse.jgit.util.GitDateFormatter.Format;

import java.util.List;
import java.util.Objects;

public class SquashMessageFormatter {

	private final GitDateFormatter dateFormatter;

	public SquashMessageFormatter() {
		dateFormatter = new GitDateFormatter(Format.DEFAULT);
	}
	
	public String format(List<RevCommit> squashedCommits) {
		StringBuilder sb = new StringBuilder();
		sb.append("Squashed commit of the following:\n"); 
		for (RevCommit c : squashedCommits) {
			sb.append("\ncommit "); 
			sb.append(c.getName());
			sb.append("\n"); 
			sb.append(toString(Objects.requireNonNull(c.getAuthorIdent())));
			sb.append("\n\t"); 
			sb.append(c.getShortMessage());
			sb.append("\n"); 
		}
		return sb.toString();
	}

	private String toString(PersonIdent author) {

		return "Author: " +
				author.getName() +
				" <" +
				author.getEmailAddress() +
				">\n" +
				"Date:   " +
				dateFormatter.formatDate(author) +
				"\n";
	}
}
