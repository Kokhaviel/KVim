/*
 * Copyright (C) 2013, Christian Halstrick <christian.halstrick@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.lib;

import java.text.MessageFormat;

import org.eclipse.jgit.errors.IllegalTodoFileModification;
import org.eclipse.jgit.internal.JGitText;

public class RebaseTodoLine {

	public enum Action {
		PICK("pick", "p"),
		REWORD("reword", "r"),
		EDIT("edit", "e"),
		SQUASH("squash", "s"),
		FIXUP("fixup", "f"),
		COMMENT("comment", "#");

		private final String token;

		private final String shortToken;

		Action(String token, String shortToken) {
			this.token = token;
			this.shortToken = shortToken;
		}

		public String toToken() {
			return this.token;
		}

		@Override
		public String toString() {
			return "Action[" + token + "]";
		}

		public static Action parse(String token) {
			for(Action action : Action.values()) {
				if(action.token.equals(token)
						|| action.shortToken.equals(token))
					return action;
			}
			throw new IllegalArgumentException(MessageFormat.format(
					JGitText.get().unknownOrUnsupportedCommand, token,
					Action.values()));
		}
	}

	Action action;
	final AbbreviatedObjectId commit;
	String shortMessage;
	String comment;

	public RebaseTodoLine(String newComment) {
		this.action = Action.COMMENT;
		setComment(newComment);
		this.commit = null;
		this.shortMessage = null;
	}

	public RebaseTodoLine(Action action, AbbreviatedObjectId commit,
						  String shortMessage) {
		this.action = action;
		this.commit = commit;
		this.shortMessage = shortMessage;
		this.comment = null;
	}

	public Action getAction() {
		return action;
	}

	public void setAction(Action newAction) throws IllegalTodoFileModification {
		if(!Action.COMMENT.equals(action) && Action.COMMENT.equals(newAction)) {
			if(comment == null)
				comment = "# " + action.token + " "
						+ ((commit == null) ? "null" : commit.name()) + " "
						+ ((shortMessage == null) ? "null" : shortMessage);
		} else if(Action.COMMENT.equals(action) && !Action.COMMENT.equals(newAction)) {
			if(commit == null)
				throw new IllegalTodoFileModification(MessageFormat.format(
						JGitText.get().cannotChangeActionOnComment, action,
						newAction));
		}
		this.action = newAction;
	}

	public void setComment(String newComment) {
		if(newComment == null) {
			this.comment = null;
			return;
		}

		if(newComment.contains("\n") || newComment.contains("\r"))
			throw createInvalidCommentException(newComment);

		if(newComment.trim().length() == 0 || newComment.startsWith("#")) {
			this.comment = newComment;
			return;
		}

		throw createInvalidCommentException(newComment);
	}

	private static IllegalArgumentException createInvalidCommentException(
			String newComment) {
		return new IllegalArgumentException(
				MessageFormat.format(
						JGitText.get().argumentIsNotAValidCommentString, newComment));
	}

	public AbbreviatedObjectId getCommit() {
		return commit;
	}

	public String getShortMessage() {
		return shortMessage;
	}

	public String getComment() {
		return comment;
	}

	@Override
	public String toString() {
		return "Step["
				+ action
				+ ", "
				+ ((commit == null) ? "null" : commit)
				+ ", "
				+ ((shortMessage == null) ? "null" : shortMessage)
				+ ", "
				+ ((comment == null) ? "" : comment) + "]";
	}
}
