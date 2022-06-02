/*
 * Copyright (C) 2009, 2013 Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.util.io;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.text.MessageFormat;

import org.eclipse.jgit.internal.JGitText;

public class TimeoutInputStream extends FilterInputStream {
	private final InterruptTimer myTimer;

	private int timeout;

	public TimeoutInputStream(final InputStream src,
			final InterruptTimer timer) {
		super(src);
		myTimer = timer;
	}

	public int getTimeout() {
		return timeout;
	}

	public void setTimeout(int millis) {
		if (millis < 0)
			throw new IllegalArgumentException(MessageFormat.format(
					JGitText.get().invalidTimeout, millis));
		timeout = millis;
	}

	@Override
	public int read() throws IOException {
		try {
			beginRead();
			return super.read();
		} catch (InterruptedIOException e) {
			throw readTimedOut(e);
		} finally {
			endRead();
		}
	}

	@Override
	public int read(byte[] buf) throws IOException {
		return read(buf, 0, buf.length);
	}

	@Override
	public int read(byte[] buf, int off, int cnt) throws IOException {
		try {
			beginRead();
			return super.read(buf, off, cnt);
		} catch (InterruptedIOException e) {
			throw readTimedOut(e);
		} finally {
			endRead();
		}
	}

	@Override
	public long skip(long cnt) throws IOException {
		try {
			beginRead();
			return super.skip(cnt);
		} catch (InterruptedIOException e) {
			throw readTimedOut(e);
		} finally {
			endRead();
		}
	}

	private void beginRead() {
		myTimer.begin(timeout);
	}

	private void endRead() {
		myTimer.end();
	}

	private InterruptedIOException readTimedOut(InterruptedIOException e) {
		InterruptedIOException interrupted = new InterruptedIOException(
				MessageFormat.format(JGitText.get().readTimedOut,
						timeout));
		interrupted.initCause(e);
		return interrupted;
	}
}
