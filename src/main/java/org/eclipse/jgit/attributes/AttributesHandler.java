/*
 * Copyright (C) 2015, 2022 Ivan Motsch <ivan.motsch@bsiag.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.attributes;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.attributes.Attribute.State;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.TreeWalk.OperationType;
import org.eclipse.jgit.treewalk.WorkingTreeIterator;

public class AttributesHandler {
	private static final String MACRO_PREFIX = "[attr]";
	private static final String BINARY_RULE_KEY = "binary";

	private static final List<Attribute> BINARY_RULE_ATTRIBUTES = new AttributesRule(
			MACRO_PREFIX + BINARY_RULE_KEY, "-diff -merge -text").getAttributes();

	private final TreeWalk treeWalk;
	private final Supplier<CanonicalTreeParser> attributesTree;
	private final AttributesNode globalNode;
	private final AttributesNode infoNode;
	private final Map<String, List<Attribute>> expansions = new HashMap<>();

	public AttributesHandler(TreeWalk treeWalk,
							 Supplier<CanonicalTreeParser> attributesTree) throws IOException {
		this.treeWalk = treeWalk;
		this.attributesTree = attributesTree;
		AttributesNodeProvider attributesNodeProvider = treeWalk.getAttributesNodeProvider();
		this.globalNode = attributesNodeProvider != null ? attributesNodeProvider.getGlobalAttributesNode() : null;
		this.infoNode = attributesNodeProvider != null ? attributesNodeProvider.getInfoAttributesNode() : null;

		AttributesNode rootNode = attributesNode(treeWalk, rootOf(treeWalk.getTree(WorkingTreeIterator.class)),
				rootOf(treeWalk.getTree(DirCacheIterator.class)), rootOf(attributesTree.get()));

		expansions.put(BINARY_RULE_KEY, BINARY_RULE_ATTRIBUTES);
		for(AttributesNode node : new AttributesNode[] {globalNode, rootNode,
				infoNode}) {
			if(node == null) {
				continue;
			}
			for(AttributesRule rule : node.getRules()) {
				if(rule.getPattern().startsWith(MACRO_PREFIX)) {
					expansions.put(rule.getPattern()
									.substring(MACRO_PREFIX.length()).trim(),
							rule.getAttributes());
				}
			}
		}
	}

	public Attributes getAttributes() throws IOException {
		String entryPath = treeWalk.getPathString();
		boolean isDirectory = (treeWalk.getFileMode() == FileMode.TREE);
		Attributes attributes = new Attributes();

		mergeInfoAttributes(entryPath, isDirectory, attributes);

		mergePerDirectoryEntryAttributes(entryPath, entryPath.lastIndexOf('/'),
				isDirectory,
				treeWalk.getTree(WorkingTreeIterator.class),
				treeWalk.getTree(DirCacheIterator.class),
				attributesTree.get(),
				attributes);

		mergeGlobalAttributes(entryPath, isDirectory, attributes);

		for(Attribute a : attributes.getAll()) {
			if(a.getState() == State.UNSPECIFIED)
				attributes.remove(a.getKey());
		}

		return attributes;
	}

	private void mergeGlobalAttributes(String entryPath, boolean isDirectory,
									   Attributes result) {
		mergeAttributes(globalNode, entryPath, isDirectory, result);
	}

	private void mergeInfoAttributes(String entryPath, boolean isDirectory,
									 Attributes result) {
		mergeAttributes(infoNode, entryPath, isDirectory, result);
	}

	private void mergePerDirectoryEntryAttributes(String entryPath, int nameRoot, boolean isDirectory,
												  @Nullable WorkingTreeIterator workingTreeIterator, @Nullable DirCacheIterator dirCacheIterator,
												  @Nullable CanonicalTreeParser otherTree, Attributes result) throws IOException {
		if(workingTreeIterator != null || dirCacheIterator != null || otherTree != null) {
			AttributesNode attributesNode = attributesNode(treeWalk, workingTreeIterator, dirCacheIterator, otherTree);
			if(attributesNode != null) {
				mergeAttributes(attributesNode, entryPath.substring(nameRoot + 1), isDirectory, result);
			}
			mergePerDirectoryEntryAttributes(entryPath, entryPath.lastIndexOf('/', nameRoot - 1), isDirectory,
					parentOf(workingTreeIterator), parentOf(dirCacheIterator), parentOf(otherTree), result);
		}
	}

	protected void mergeAttributes(@Nullable AttributesNode node, String entryPath,
								   boolean isDirectory, Attributes result) {
		if(node == null) return;
		List<AttributesRule> rules = node.getRules();
		ListIterator<AttributesRule> ruleIterator = rules.listIterator(rules.size());
		while(ruleIterator.hasPrevious()) {
			AttributesRule rule = ruleIterator.previous();
			if(rule.isMatch(entryPath, isDirectory)) {
				ListIterator<Attribute> attributeIte = rule.getAttributes().listIterator(rule.getAttributes().size());
				while(attributeIte.hasPrevious()) {
					expandMacro(attributeIte.previous(), result);
				}
			}
		}
	}

	protected void expandMacro(Attribute attr, Attributes result) {
		if(result.containsKey(attr.getKey())) return;

		result.put(attr);
		List<Attribute> expansion = expansions.get(attr.getKey());
		if(expansion == null) return;
		switch(attr.getState()) {
			case UNSET: {
				for(Attribute e : expansion) {
					switch(e.getState()) {
						case SET:
							expandMacro(new Attribute(e.getKey(), State.UNSET), result);
							break;
						case UNSET:
							expandMacro(new Attribute(e.getKey(), State.SET), result);
							break;
						case UNSPECIFIED:
							expandMacro(new Attribute(e.getKey(), State.UNSPECIFIED),
									result);
							break;
						case CUSTOM:
						default:
							expandMacro(e, result);
					}
				}
				break;
			}
			case CUSTOM: {
				for(Attribute e : expansion) {
					switch(e.getState()) {
						case SET:
						case UNSET:
						case UNSPECIFIED:
							expandMacro(e, result);
							break;
						case CUSTOM:
						default:
							expandMacro(new Attribute(e.getKey(), attr.getValue()),
									result);
					}
				}
				break;
			}
			case UNSPECIFIED: {
				for(Attribute e : expansion) {
					expandMacro(new Attribute(e.getKey(), State.UNSPECIFIED),
							result);
				}
				break;
			}
			case SET:
			default:
				for(Attribute e : expansion) {
					expandMacro(e, result);
				}
				break;
		}
	}

	private static AttributesNode attributesNode(TreeWalk treeWalk,
												 @Nullable WorkingTreeIterator workingTreeIterator,
												 @Nullable DirCacheIterator dirCacheIterator,
												 @Nullable CanonicalTreeParser otherTree) throws IOException {
		AttributesNode attributesNode = null;
		switch(treeWalk.getOperationType()) {
			case CHECKIN_OP:
				if(workingTreeIterator != null) {
					attributesNode = workingTreeIterator.getEntryAttributesNode();
				}
				if(attributesNode == null && dirCacheIterator != null) {
					attributesNode = dirCacheIterator
							.getEntryAttributesNode(treeWalk.getObjectReader());
				}
				if(attributesNode == null && otherTree != null) {
					attributesNode = otherTree
							.getEntryAttributesNode(treeWalk.getObjectReader());
				}
				break;
			case CHECKOUT_OP:
				if(otherTree != null) {
					attributesNode = otherTree
							.getEntryAttributesNode(treeWalk.getObjectReader());
				}
				if(attributesNode == null && dirCacheIterator != null) {
					attributesNode = dirCacheIterator
							.getEntryAttributesNode(treeWalk.getObjectReader());
				}
				if(attributesNode == null && workingTreeIterator != null) {
					attributesNode = workingTreeIterator.getEntryAttributesNode();
				}
				break;
			default:
				throw new IllegalStateException(
						"The only supported operation types are:"
								+ OperationType.CHECKIN_OP + ","
								+ OperationType.CHECKOUT_OP);
		}

		return attributesNode;
	}

	private static <T extends AbstractTreeIterator> T parentOf(@Nullable T node) {
		if(node == null) return null;
		@SuppressWarnings("unchecked")
		Class<T> type = (Class<T>) node.getClass();
		AbstractTreeIterator parent = node.parent;
		if(type.isInstance(parent)) {
			return type.cast(parent);
		}
		return null;
	}

	private static <T extends AbstractTreeIterator> T rootOf(
			@Nullable T node) {
		if(node == null) return null;
		AbstractTreeIterator t = node;
		while(t.parent != null) {
			t = t.parent;
		}
		@SuppressWarnings("unchecked")
		Class<T> type = (Class<T>) node.getClass();
		if(type.isInstance(t)) {
			return type.cast(t);
		}
		return null;
	}

}
