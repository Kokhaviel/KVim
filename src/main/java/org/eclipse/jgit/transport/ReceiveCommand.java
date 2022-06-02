/*
 * Copyright (C) 2008, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import static org.eclipse.jgit.transport.ReceiveCommand.Result.NOT_ATTEMPTED;
import static org.eclipse.jgit.transport.ReceiveCommand.Result.REJECTED_OTHER_REASON;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevWalk;

public class ReceiveCommand {
	public enum Type {
		CREATE,
		UPDATE,
		UPDATE_NONFASTFORWARD,
		DELETE
	}

	public enum Result {
		NOT_ATTEMPTED,
		REJECTED_NOCREATE,
		REJECTED_NODELETE,
		REJECTED_NONFASTFORWARD,
		REJECTED_CURRENT_BRANCH,
		REJECTED_MISSING_OBJECT,
		REJECTED_OTHER_REASON,
		LOCK_FAILURE,
		OK
	}

	public static List<ReceiveCommand> filter(Iterable<ReceiveCommand> in,
											  Result want) {
		List<ReceiveCommand> r;
		if(in instanceof Collection)
			r = new ArrayList<>(((Collection<?>) in).size());
		else
			r = new ArrayList<>();
		for(ReceiveCommand cmd : in) {
			if(cmd.getResult() == want)
				r.add(cmd);
		}
		return r;
	}

	public static List<ReceiveCommand> filter(List<ReceiveCommand> commands,
											  Result want) {
		return filter((Iterable<ReceiveCommand>) commands, want);
	}

	public static void abort(Iterable<ReceiveCommand> commands) {
		for(ReceiveCommand c : commands) {
			if(c.getResult() == NOT_ATTEMPTED) {
				c.setResult(REJECTED_OTHER_REASON,
						JGitText.get().transactionAborted);
			}
		}
	}

	public static ReceiveCommand link(@NonNull ObjectId oldId,
									  @NonNull String newTarget, @NonNull String name) {
		return new ReceiveCommand(oldId, newTarget, name);
	}

	public static ReceiveCommand link(@Nullable String oldTarget,
									  @NonNull String newTarget, @NonNull String name) {
		return new ReceiveCommand(oldTarget, newTarget, name);
	}

	public static ReceiveCommand unlink(@NonNull String oldTarget,
										@NonNull ObjectId newId, @NonNull String name) {
		return new ReceiveCommand(oldTarget, newId, name);
	}

	private final ObjectId oldId;
	private final String oldSymref;
	private final ObjectId newId;
	private final String newSymref;
	private final String name;
	private Type type;
	private boolean typeIsCorrect;
	private Ref ref;
	private Result status = Result.NOT_ATTEMPTED;
	private String message;
	private boolean customRefLog;
	private String refLogMessage;
	private boolean refLogIncludeResult;
	private Boolean forceRefLog;

	public ReceiveCommand(final ObjectId oldId, final ObjectId newId,
						  final String name) {
		if(oldId == null) {
			throw new IllegalArgumentException(
					JGitText.get().oldIdMustNotBeNull);
		}
		if(newId == null) {
			throw new IllegalArgumentException(
					JGitText.get().newIdMustNotBeNull);
		}
		if(name == null || name.isEmpty()) {
			throw new IllegalArgumentException(
					JGitText.get().nameMustNotBeNullOrEmpty);
		}
		this.oldId = oldId;
		this.oldSymref = null;
		this.newId = newId;
		this.newSymref = null;
		this.name = name;

		type = Type.UPDATE;
		if(ObjectId.zeroId().equals(oldId)) {
			type = Type.CREATE;
		}
		if(ObjectId.zeroId().equals(newId)) {
			type = Type.DELETE;
		}
	}

	private ReceiveCommand(ObjectId oldId, String newSymref, String name) {
		if(oldId == null) {
			throw new IllegalArgumentException(
					JGitText.get().oldIdMustNotBeNull);
		}
		if(name == null || name.isEmpty()) {
			throw new IllegalArgumentException(
					JGitText.get().nameMustNotBeNullOrEmpty);
		}
		this.oldId = oldId;
		this.oldSymref = null;
		this.newId = ObjectId.zeroId();
		this.newSymref = newSymref;
		this.name = name;
		if(AnyObjectId.isEqual(ObjectId.zeroId(), oldId)) {
			type = Type.CREATE;
		} else if(newSymref != null) {
			type = Type.UPDATE;
		} else {
			type = Type.DELETE;
		}
		typeIsCorrect = true;
	}

	private ReceiveCommand(String oldSymref, ObjectId newId, String name) {
		if(newId == null) {
			throw new IllegalArgumentException(
					JGitText.get().newIdMustNotBeNull);
		}
		if(name == null || name.isEmpty()) {
			throw new IllegalArgumentException(
					JGitText.get().nameMustNotBeNullOrEmpty);
		}
		this.oldId = ObjectId.zeroId();
		this.oldSymref = oldSymref;
		this.newId = newId;
		this.newSymref = null;
		this.name = name;
		if(oldSymref == null) {
			type = Type.CREATE;
		} else if(!AnyObjectId.isEqual(ObjectId.zeroId(), newId)) {
			type = Type.UPDATE;
		} else {
			type = Type.DELETE;
		}
		typeIsCorrect = true;
	}

	private ReceiveCommand(@Nullable String oldTarget, String newTarget, String name) {
		if(name == null || name.isEmpty()) {
			throw new IllegalArgumentException(
					JGitText.get().nameMustNotBeNullOrEmpty);
		}
		this.oldId = ObjectId.zeroId();
		this.oldSymref = oldTarget;
		this.newId = ObjectId.zeroId();
		this.newSymref = newTarget;
		this.name = name;
		if(oldTarget == null) {
			if(newTarget == null) {
				throw new IllegalArgumentException(
						JGitText.get().bothRefTargetsMustNotBeNull);
			}
			type = Type.CREATE;
		} else if(newTarget != null) {
			type = Type.UPDATE;
		} else {
			type = Type.DELETE;
		}
		typeIsCorrect = true;
	}

	public ObjectId getOldId() {
		return oldId;
	}

	@Nullable
	public String getOldSymref() {
		return oldSymref;
	}

	public ObjectId getNewId() {
		return newId;
	}

	@Nullable
	public String getNewSymref() {
		return newSymref;
	}

	public String getRefName() {
		return name;
	}

	public Type getType() {
		return type;
	}

	public Ref getRef() {
		return ref;
	}

	public Result getResult() {
		return status;
	}

	public String getMessage() {
		return message;
	}

	public boolean hasCustomRefLog() {
		return customRefLog;
	}

	public boolean isRefLogDisabled() {
		return refLogMessage == null;
	}

	@Nullable
	public String getRefLogMessage() {
		return refLogMessage;
	}

	public boolean isRefLogIncludingResult() {
		return refLogIncludeResult;
	}

	@Nullable
	public Boolean isForceRefLog() {
		return forceRefLog;
	}

	public void setResult(Result s) {
		setResult(s, null);
	}

	public void setResult(Result s, String m) {
		status = s;
		message = m;
	}

	public void updateType(RevWalk walk) throws IOException {
		if(typeIsCorrect)
			return;
		if(type == Type.UPDATE && !AnyObjectId.isEqual(oldId, newId)) {
			RevObject o = walk.parseAny(oldId);
			RevObject n = walk.parseAny(newId);
			if(!(o instanceof RevCommit)
					|| !(n instanceof RevCommit)
					|| !walk.isMergedInto((RevCommit) o, (RevCommit) n))
				setType();
		}
		typeIsCorrect = true;
	}

	void setRef(Ref r) {
		ref = r;
	}

	void setType() {
		type = Type.UPDATE_NONFASTFORWARD;
	}

	void setTypeFastForwardUpdate() {
		type = Type.UPDATE;
		typeIsCorrect = true;
	}

	public void setResult(RefUpdate.Result r) {
		switch(r) {
			case NOT_ATTEMPTED:
				setResult(Result.NOT_ATTEMPTED);
				break;

			case LOCK_FAILURE:
			case IO_FAILURE:
				setResult(Result.LOCK_FAILURE);
				break;

			case NO_CHANGE:
			case NEW:
			case FORCED:
			case FAST_FORWARD:
				setResult(Result.OK);
				break;

			case REJECTED:
				setResult(Result.REJECTED_NONFASTFORWARD);
				break;

			case REJECTED_CURRENT_BRANCH:
				setResult(Result.REJECTED_CURRENT_BRANCH);
				break;

			case REJECTED_MISSING_OBJECT:
				setResult(Result.REJECTED_MISSING_OBJECT);
				break;

			case REJECTED_OTHER_REASON:
				setResult(Result.REJECTED_OTHER_REASON);
				break;

			default:
				setResult(Result.REJECTED_OTHER_REASON, r.name());
				break;
		}
	}

	void reject(IOException err) {
		setResult(Result.REJECTED_OTHER_REASON, MessageFormat.format(
				JGitText.get().lockError, err.getMessage()));
	}

	@Override
	public String toString() {
		return getType().name() + ": " + getOldId().name() + " "
				+ getNewId().name() + " " + getRefName();
	}
}
