/*
 * Copyright (C) 2015 Obeo. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.hooks;

import java.io.PrintStream;
import java.text.MessageFormat;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.util.LfsFactory;

public class Hooks {

	public static PreCommitHook preCommit(Repository repo,
										  PrintStream outputStream, PrintStream errorStream) {
		return new PreCommitHook(repo, outputStream, errorStream);
	}

	public static PostCommitHook postCommit(Repository repo,
											PrintStream outputStream, PrintStream errorStream) {
		return new PostCommitHook(repo, outputStream, errorStream);
	}

	public static CommitMsgHook commitMsg(Repository repo,
										  PrintStream outputStream, PrintStream errorStream) {
		return new CommitMsgHook(repo, outputStream, errorStream);
	}

	public static PrePushHook prePush(Repository repo, PrintStream outputStream) {
		if(LfsFactory.getInstance().isAvailable()) {
			PrePushHook hook = LfsFactory.getInstance().getPrePushHook(
			);
			if(hook != null) {
				if(hook.isNativeHookPresent()) {
					PrintStream ps = outputStream;
					if(ps == null) {
						ps = System.out;
					}
					ps.println(MessageFormat
							.format(JGitText.get().lfsHookConflict, repo));
				}
				return hook;
			}
		}
		return new PrePushHook(repo, outputStream);
	}

}
