/*
 * Copyright (C) 2009-2010, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.util.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;

public class StreamCopyThread extends Thread {
	private static final int BUFFER_SIZE = 1024;

	private final InputStream src;
	private final OutputStream dst;
	private volatile boolean done;
	private final Object writeLock;

	public StreamCopyThread(InputStream i, OutputStream o) {
		setName(Thread.currentThread().getName() + "-StreamCopy");
		src = i;
		dst = o;
		writeLock = new Object();
	}

	public void halt() throws InterruptedException {
		for (;;) {
			join(250);
			if (isAlive()) {
				done = true;
				interrupt();
			} else
				break;
		}
	}

	@Override
	public void run() {
		try {
			final byte[] buf = new byte[BUFFER_SIZE];
			boolean readInterrupted = false;
			for (;;) {
				try {
					if (readInterrupted) {
						synchronized (writeLock) {
							boolean interruptedAgain = Thread.interrupted();
							dst.flush();
							if (interruptedAgain) {
								interrupt();
							}
						}
						readInterrupted = false;
					}

					if (done)
						break;

					final int n;
					try {
						n = src.read(buf);
					} catch (InterruptedIOException wakey) {
						readInterrupted = true;
						continue;
					}
					if (n < 0)
						break;

					synchronized (writeLock) {
						boolean writeInterrupted = Thread.interrupted();
						dst.write(buf, 0, n);
						if (writeInterrupted) {
							interrupt();
						}
					}
				} catch (IOException e) {
					break;
				}
			}
		} finally {
			try {
				src.close();
			} catch (IOException ignored) {
			}
			try {
				dst.close();
			} catch (IOException ignored) {
			}
		}
	}
}
