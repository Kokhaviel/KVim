/*
 * Copyright (C) 2010, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.errors;

import java.text.MessageFormat;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.ObjectId;

public class LargeObjectException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	private ObjectId objectId;

	public LargeObjectException() {
	}

	public LargeObjectException(Throwable cause) {
		initCause(cause);
	}

	public LargeObjectException(AnyObjectId id) {
		setObjectId(id);
	}

	public ObjectId getObjectId() {
		return objectId;
	}

	protected String getObjectName() {
		if (getObjectId() != null)
			return getObjectId().name();
		return JGitText.get().unknownObject;
	}

	public void setObjectId(AnyObjectId id) {
		if (objectId == null)
			objectId = id.copy();
	}

	@Override
	public String getMessage() {
		return MessageFormat.format(JGitText.get().largeObjectException,
				getObjectName());
	}

	public static class OutOfMemory extends LargeObjectException {
		private static final long serialVersionUID = 1L;

		public OutOfMemory(OutOfMemoryError cause) {
			initCause(cause);
		}

		@Override
		public String getMessage() {
			return MessageFormat.format(JGitText.get().largeObjectOutOfMemory,
					getObjectName());
		}
	}

	public static class ExceedsByteArrayLimit extends LargeObjectException {
		private static final long serialVersionUID = 1L;

		@Override
		public String getMessage() {
			return MessageFormat
					.format(JGitText.get().largeObjectExceedsByteArray,
							getObjectName());
		}
	}

	public static class ExceedsLimit extends LargeObjectException {
		private static final long serialVersionUID = 1L;

		private final long limit;
		private final long size;

		public ExceedsLimit(long limit, long size) {
			this.limit = limit;
			this.size = size;
		}

		@Override
		public String getMessage() {
			return MessageFormat.format(JGitText.get().largeObjectExceedsLimit, getObjectName(), limit, size);
		}
	}
}
