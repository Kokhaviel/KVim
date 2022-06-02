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

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.text.MessageFormat;

import org.eclipse.jgit.internal.JGitText;

public class TimeoutOutputStream extends OutputStream {
	private final OutputStream dst;

	private final InterruptTimer myTimer;

	private int timeout;

	public TimeoutOutputStream(final OutputStream destination,
			final InterruptTimer timer) {
		dst = destination;
		myTimer = timer;
	}

	public void setTimeout(int millis) {
		if (millis < 0)
			throw new IllegalArgumentException(MessageFormat.format(
					JGitText.get().invalidTimeout, millis));
		timeout = millis;
	}

	@Override
	public void write(int b) throws IOException {
		try {
			beginWrite();
			dst.write(b);
		} catch (InterruptedIOException e) {
			throw writeTimedOut(e);
		} finally {
			endWrite();
		}
	}

	@Override
	public void write(byte[] buf) throws IOException {
		write(buf, 0, buf.length);
	}

	@Override
	public void write(byte[] buf, int off, int len) throws IOException {
		try {
			beginWrite();
			dst.write(buf, off, len);
		} catch (InterruptedIOException e) {
			throw writeTimedOut(e);
		} finally {
			endWrite();
		}
	}

	@Override
	public void flush() throws IOException {
		try {
			beginWrite();
			dst.flush();
		} catch (InterruptedIOException e) {
			throw writeTimedOut(e);
		} finally {
			endWrite();
		}
	}

	@Override
	public void close() throws IOException {
		try {
			beginWrite();
			dst.close();
		} catch (InterruptedIOException e) {
			throw writeTimedOut(e);
		} finally {
			endWrite();
		}
	}

	private void beginWrite() {
		myTimer.begin(timeout);
	}

	private void endWrite() {
		myTimer.end();
	}

	private InterruptedIOException writeTimedOut(InterruptedIOException cause) {
		InterruptedIOException e = new InterruptedIOException(
				MessageFormat.format(JGitText.get().writeTimedOut,
						timeout));
		e.initCause(cause);
		return e;
	}
}
