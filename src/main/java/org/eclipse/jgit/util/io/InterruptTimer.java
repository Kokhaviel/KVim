/*
 * Copyright (C) 2009, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.util.io;

import java.text.MessageFormat;

import org.eclipse.jgit.internal.JGitText;

public final class InterruptTimer {
	private final AlarmState state;

	private final AlarmThread thread;

	final AutoKiller autoKiller;

	public InterruptTimer(String threadName) {
		state = new AlarmState();
		autoKiller = new AutoKiller(state);
		thread = new AlarmThread(threadName, state);
		thread.start();
	}

	public void begin(int timeout) {
		if (timeout <= 0)
			throw new IllegalArgumentException(MessageFormat.format(
					JGitText.get().invalidTimeout, timeout));
		Thread.interrupted();
		state.begin(timeout);
	}

	public void end() {
		state.end();
	}

	public void terminate() {
		state.terminate();
		try {
			thread.join();
		} catch (InterruptedException ignored) {
		}
	}

	static final class AlarmThread extends Thread {
		AlarmThread(String name, AlarmState q) {
			super(q);
			setName(name);
			setDaemon(true);
		}
	}

	private static final class AutoKiller {
		private final AlarmState state;

		AutoKiller(AlarmState s) {
			state = s;
		}

		@Override
		protected void finalize() {
			state.terminate();
		}
	}

	static final class AlarmState implements Runnable {
		private Thread callingThread;

		private long deadline;

		private boolean terminated;

		AlarmState() {
			callingThread = Thread.currentThread();
		}

		@Override
		public synchronized void run() {
			while (!terminated && callingThread.isAlive()) {
				try {
					if (0 < deadline) {
						final long delay = deadline - now();
						if (delay <= 0) {
							deadline = 0;
							callingThread.interrupt();
						} else {
							wait(delay);
						}
					} else {
						wait(1000);
					}
				} catch (InterruptedException ignored) {
				}
			}
		}

		synchronized void begin(int timeout) {
			if (terminated)
				throw new IllegalStateException(JGitText.get().timerAlreadyTerminated);
			callingThread = Thread.currentThread();
			deadline = now() + timeout;
			notifyAll();
		}

		synchronized void end() {
			if (0 == deadline)
				Thread.interrupted();
			else
				deadline = 0;
			notifyAll();
		}

		synchronized void terminate() {
			if (!terminated) {
				deadline = 0;
				terminated = true;
				notifyAll();
			}
		}

		private static long now() {
			return System.currentTimeMillis();
		}
	}
}
