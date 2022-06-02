/*
 * Copyright (C) 2008, Marek Zawirski <marek.zawirski@gmail.com>
 * Copyright (C) 2008, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import static org.eclipse.jgit.lib.Constants.OBJECT_ID_ABBREV_STRING_LENGTH;

import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;

public class TrackingRefUpdate {
	private final String remoteName;
	final String localName;
	boolean forceUpdate;
	ObjectId oldObjectId;
	ObjectId newObjectId;

	private RefUpdate.Result result;
	private ReceiveCommand cmd;

	TrackingRefUpdate(
			boolean canForceUpdate,
			String remoteName,
			String localName,
			AnyObjectId oldValue,
			AnyObjectId newValue) {
		this.remoteName = remoteName;
		this.localName = localName;
		this.forceUpdate = canForceUpdate;
		this.oldObjectId = oldValue.copy();
		this.newObjectId = newValue.copy();
	}

	public String getRemoteName() {
		return remoteName;
	}

	public String getLocalName() {
		return localName;
	}

	public ObjectId getNewObjectId() {
		return newObjectId;
	}

	public ObjectId getOldObjectId() {
		return oldObjectId;
	}

	public RefUpdate.Result getResult() {
		return result;
	}

	void setResult(RefUpdate.Result result) {
		this.result = result;
	}

	public ReceiveCommand asReceiveCommand() {
		if(cmd == null)
			cmd = new Command();
		return cmd;
	}

	final class Command extends ReceiveCommand {
		Command() {
			super(oldObjectId, newObjectId, localName);
		}

		boolean canForceUpdate() {
			return forceUpdate;
		}

		@Override
		public void setResult(RefUpdate.Result status) {
			result = status;
			super.setResult(status);
		}

		@Override
		public void setResult(ReceiveCommand.Result status) {
			result = decode(status);
			super.setResult(status);
		}

		@Override
		public void setResult(ReceiveCommand.Result status, String msg) {
			result = decode(status);
			super.setResult(status, msg);
		}

		private RefUpdate.Result decode(ReceiveCommand.Result status) {
			switch(status) {
				case OK:
					if(AnyObjectId.isEqual(oldObjectId, newObjectId))
						return RefUpdate.Result.NO_CHANGE;
					switch(getType()) {
						case CREATE:
							return RefUpdate.Result.NEW;
						case UPDATE:
							return RefUpdate.Result.FAST_FORWARD;
						case DELETE:
						case UPDATE_NONFASTFORWARD:
						default:
							return RefUpdate.Result.FORCED;
					}

				case REJECTED_NOCREATE:
				case REJECTED_NODELETE:
				case REJECTED_NONFASTFORWARD:
					return RefUpdate.Result.REJECTED;
				case REJECTED_CURRENT_BRANCH:
					return RefUpdate.Result.REJECTED_CURRENT_BRANCH;
				case REJECTED_MISSING_OBJECT:
					return RefUpdate.Result.IO_FAILURE;
				case LOCK_FAILURE:
				case NOT_ATTEMPTED:
				case REJECTED_OTHER_REASON:
				default:
					return RefUpdate.Result.LOCK_FAILURE;
			}
		}
	}

	@SuppressWarnings("nls")
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append("TrackingRefUpdate[");
		sb.append(remoteName);
		sb.append(" -> ");
		sb.append(localName);
		if(forceUpdate)
			sb.append(" (forced)");
		sb.append(" ");
		sb.append(oldObjectId == null ? ""
				: oldObjectId.abbreviate(OBJECT_ID_ABBREV_STRING_LENGTH)
				.name());
		sb.append("..");
		sb.append(newObjectId == null ? ""
				: newObjectId.abbreviate(OBJECT_ID_ABBREV_STRING_LENGTH)
				.name());
		sb.append("]");
		return sb.toString();
	}
}
