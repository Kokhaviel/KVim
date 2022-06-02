/*
 * Copyright (C) 2010, 2021 Red Hat Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.ignore;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.errors.InvalidPatternException;
import org.eclipse.jgit.internal.JGitText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IgnoreNode {

	private static final Logger LOG = LoggerFactory.getLogger(IgnoreNode.class);
	private final List<FastIgnoreRule> rules;

	public IgnoreNode() {
		this(new ArrayList<>());
	}

	public IgnoreNode(List<FastIgnoreRule> rules) {
		this.rules = rules;
	}

	public void parse(String sourceName, InputStream in) throws IOException {
		BufferedReader br = asReader(in);
		String txt;
		int lineNumber = 1;
		while ((txt = br.readLine()) != null) {
			if (txt.length() > 0 && !txt.startsWith("#") && !txt.equals("/")) {
				FastIgnoreRule rule = new FastIgnoreRule();
				try {
					rule.parse(txt);
				} catch (InvalidPatternException e) {
					if (sourceName != null) {
						LOG.error(MessageFormat.format(
								JGitText.get().badIgnorePatternFull, sourceName,
								Integer.toString(lineNumber), e.getPattern(),
								e.getLocalizedMessage()), e);
					} else {
						LOG.error(MessageFormat.format(
								JGitText.get().badIgnorePattern,
								e.getPattern()), e);
					}
				}
				if (!rule.isEmpty()) {
					rules.add(rule);
				}
			}
			lineNumber++;
		}
	}

	private static BufferedReader asReader(InputStream in) {
		return new BufferedReader(new InputStreamReader(in, UTF_8));
	}

	public List<FastIgnoreRule> getRules() {
		return Collections.unmodifiableList(rules);
	}

	public @Nullable Boolean checkIgnored(String entryPath, boolean isDirectory) {
		for (int i = rules.size() - 1; i > -1; i--) {
			FastIgnoreRule rule = rules.get(i);
			if (rule.isMatch(entryPath, isDirectory, true)) {
				return rule.getResult();
			}
		}
		return null;
	}

	@Override
	public String toString() {
		return rules.toString();
	}
}
