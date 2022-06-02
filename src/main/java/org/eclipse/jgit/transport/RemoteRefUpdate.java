/*
 * Copyright (C) 2008, Marek Zawirski <marek.zawirski@gmail.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.Collection;

import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;

public class RemoteRefUpdate {
	public enum Status {
		NOT_ATTEMPTED,
		UP_TO_DATE,
		REJECTED_NONFASTFORWARD,
		REJECTED_NODELETE,
		REJECTED_REMOTE_CHANGED,
		REJECTED_OTHER_REASON,
		NON_EXISTING,
		AWAITING_REPORT,
		OK
	}

	private ObjectId expectedOldObjectId;
	private final ObjectId newObjectId;
	private final String remoteName;
	private final TrackingRefUpdate trackingRefUpdate;
	private final String srcRef;
	private final boolean forceUpdate;
	private Status status;
	private boolean fastForward;
	private String message;
	private RefUpdate localUpdate;
	private final Collection<RefSpec> fetchSpecs;

	public RemoteRefUpdate(Repository localDb, String srcRef, String remoteName,
						   boolean forceUpdate, String localName, ObjectId expectedOldObjectId)
			throws IOException {
		this(localDb, srcRef, srcRef != null ? localDb.resolve(srcRef)
						: ObjectId.zeroId(), remoteName, forceUpdate, localName,
				expectedOldObjectId);
	}

	public RemoteRefUpdate(Repository localDb, String srcRef, ObjectId srcId,
						   String remoteName, boolean forceUpdate, String localName,
						   ObjectId expectedOldObjectId) throws IOException {
		this(localDb, srcRef, srcId, remoteName, forceUpdate, localName, null,
				expectedOldObjectId);
	}

	private RemoteRefUpdate(Repository localDb, String srcRef, ObjectId srcId,
							String remoteName, boolean forceUpdate, String localName,
							Collection<RefSpec> fetchSpecs, ObjectId expectedOldObjectId)
			throws IOException {
		if(fetchSpecs == null) {
			if(remoteName == null) {
				throw new IllegalArgumentException(
						JGitText.get().remoteNameCannotBeNull);
			}
			if(srcId == null && srcRef != null) {
				throw new IOException(MessageFormat.format(
						JGitText.get().sourceRefDoesntResolveToAnyObject,
						srcRef));
			}
		}
		if(srcRef != null) {
			this.srcRef = srcRef;
		} else if(srcId != null && !srcId.equals(ObjectId.zeroId())) {
			this.srcRef = srcId.name();
		} else {
			this.srcRef = null;
		}
		if(srcId != null) {
			this.newObjectId = srcId;
		} else {
			this.newObjectId = ObjectId.zeroId();
		}
		this.fetchSpecs = fetchSpecs;
		this.remoteName = remoteName;
		this.forceUpdate = forceUpdate;
		if(localName != null && localDb != null) {
			localUpdate = localDb.updateRef(localName);
			localUpdate.setForceUpdate(true);
			localUpdate.setRefLogMessage("push", true);
			localUpdate.setNewObjectId(newObjectId);
			trackingRefUpdate = new TrackingRefUpdate(
					true,
					remoteName,
					localName,
					localUpdate.getOldObjectId() != null
							? localUpdate.getOldObjectId()
							: ObjectId.zeroId(),
					newObjectId);
		} else {
			trackingRefUpdate = null;
		}
		this.expectedOldObjectId = expectedOldObjectId;
		this.status = Status.NOT_ATTEMPTED;
	}

	RemoteRefUpdate(boolean forceUpdate, @NonNull Collection<RefSpec> fetchSpecs) {
		this.forceUpdate = forceUpdate;
		this.fetchSpecs = fetchSpecs;
		this.trackingRefUpdate = null;
		this.srcRef = null;
		this.remoteName = null;
		this.newObjectId = null;
		this.status = Status.NOT_ATTEMPTED;
	}

	public boolean isMatching() {
		return fetchSpecs != null;
	}

	Collection<RefSpec> getFetchSpecs() {
		return fetchSpecs;
	}

	public ObjectId getExpectedOldObjectId() {
		return expectedOldObjectId;
	}

	public boolean isExpectingOldObjectId() {
		return expectedOldObjectId != null;
	}

	public ObjectId getNewObjectId() {
		return newObjectId;
	}

	public boolean isDelete() {
		return ObjectId.zeroId().equals(newObjectId);
	}

	public String getRemoteName() {
		return remoteName;
	}

	public TrackingRefUpdate getTrackingRefUpdate() {
		return trackingRefUpdate;
	}

	public String getSrcRef() {
		return srcRef;
	}

	public boolean hasTrackingRefUpdate() {
		return trackingRefUpdate != null;
	}

	public boolean isForceUpdate() {
		return forceUpdate;
	}

	public Status getStatus() {
		return status;
	}

	public String getMessage() {
		return message;
	}

	void setExpectedOldObjectId(ObjectId id) {
		expectedOldObjectId = id;
	}

	void setStatus(Status status) {
		this.status = status;
	}

	void setFastForward(boolean fastForward) {
		this.fastForward = fastForward;
	}

	void setMessage(String message) {
		this.message = message;
	}

	protected void updateTrackingRef(RevWalk walk) throws IOException {
		if(isDelete())
			trackingRefUpdate.setResult(localUpdate.delete(walk));
		else
			trackingRefUpdate.setResult(localUpdate.update(walk));
	}

	@Override
	public String toString() {
		return "RemoteRefUpdate[remoteName="
				+ remoteName
				+ ", "
				+ status
				+ ", "
				+ (expectedOldObjectId != null ? expectedOldObjectId.name()
				: "(null)") + "..."
				+ (newObjectId != null ? newObjectId.name() : "(null)")
				+ (fastForward ? ", fastForward" : "")
				+ ", srcRef=" + srcRef
				+ (forceUpdate ? ", forceUpdate" : "") + ", message="
				+ (message != null ? "\"" + message + "\"" : "null") + "]";
	}
}
