/*
 * Copyright (C) 2010, 2017 Red Hat Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.attributes;

import static org.eclipse.jgit.ignore.IMatcher.NO_MATCH;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.jgit.attributes.Attribute.State;
import org.eclipse.jgit.errors.InvalidPatternException;
import org.eclipse.jgit.ignore.FastIgnoreRule;
import org.eclipse.jgit.ignore.IMatcher;
import org.eclipse.jgit.ignore.internal.PathMatcher;

public class AttributesRule {

	private static final String ATTRIBUTES_SPLIT_REGEX = "[ \t\r]";

	private static List<Attribute> parseAttributes(String attributesLine) {
		ArrayList<Attribute> result = new ArrayList<>();
		for(String attribute : attributesLine.split(ATTRIBUTES_SPLIT_REGEX)) {
			attribute = attribute.trim();
			if(attribute.length() == 0)
				continue;

			if(attribute.startsWith("-")) {
				if(attribute.length() > 1)
					result.add(new Attribute(attribute.substring(1),
							State.UNSET));
				continue;
			}

			if(attribute.startsWith("!")) {
				if(attribute.length() > 1)
					result.add(new Attribute(attribute.substring(1),
							State.UNSPECIFIED));
				continue;
			}

			final int equalsIndex = attribute.indexOf('=');
			if(equalsIndex == -1)
				result.add(new Attribute(attribute, State.SET));
			else {
				String attributeKey = attribute.substring(0, equalsIndex);
				if(attributeKey.length() > 0) {
					String attributeValue = attribute
							.substring(equalsIndex + 1);
					result.add(new Attribute(attributeKey, attributeValue));
				}
			}
		}
		return result;
	}

	private final String pattern;
	private final List<Attribute> attributes;

	private final IMatcher matcher;

	public AttributesRule(String pattern, String attributes) {
		this.attributes = parseAttributes(attributes);

		boolean dirOnly;
		if(pattern.endsWith("/")) {
			pattern = pattern.substring(0, pattern.length() - 1);
			dirOnly = true;
		} else {
			dirOnly = false;
		}

		int slashIndex = pattern.indexOf('/');

		if(slashIndex < 0) {
		} else if(slashIndex == 0) {
		} else {
			pattern = "/" + pattern;
		}

		IMatcher candidateMatcher = NO_MATCH;
		try {
			candidateMatcher = PathMatcher.createPathMatcher(pattern,
					FastIgnoreRule.PATH_SEPARATOR, dirOnly);
		} catch(InvalidPatternException ignored) {
		}
		this.matcher = candidateMatcher;
		this.pattern = pattern;
	}

	public List<Attribute> getAttributes() {
		return Collections.unmodifiableList(attributes);
	}

	public String getPattern() {
		return pattern;
	}

	public boolean isMatch(String relativeTarget, boolean isDirectory) {
		if(relativeTarget == null)
			return false;
		if(relativeTarget.length() == 0)
			return false;
		return matcher.matches(relativeTarget, isDirectory, true);
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append(pattern);
		for(Attribute a : attributes) {
			sb.append(" ");
			sb.append(a);
		}
		return sb.toString();

	}
}
