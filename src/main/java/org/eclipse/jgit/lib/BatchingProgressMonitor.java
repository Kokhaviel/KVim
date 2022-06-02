/*
 * Copyright (C) 2008-2011, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.lib;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jgit.lib.internal.WorkQueue;

public abstract class BatchingProgressMonitor implements ProgressMonitor {

	private long delayStartTime;
	private TimeUnit delayStartUnit = TimeUnit.MILLISECONDS;
	private Task task;

	public void setDelayStart(long time, TimeUnit unit) {
		delayStartTime = time;
		delayStartUnit = unit;
	}

	@Override
	public void start(int totalTasks) {
	}

	@Override
	public void beginTask(String title, int work) {
		endTask();
		task = new Task(title, work);
		if(delayStartTime != 0)
			task.delay(delayStartTime, delayStartUnit);
	}

	@Override
	public void update(int completed) {
		if(task != null)
			task.update(this, completed);
	}

	@Override
	public void endTask() {
		if(task != null) {
			task.end(this);
			task = null;
		}
	}

	@Override
	public boolean isCancelled() {
		return false;
	}

	protected abstract void onUpdate(String taskName, int workCurr);

	protected abstract void onEndTask(String taskName, int workCurr);

	protected abstract void onUpdate(String taskName, int workCurr,
									 int workTotal, int percentDone);

	protected abstract void onEndTask(String taskName, int workCurr,
									  int workTotal, int percentDone);

	private static class Task implements Runnable {

		private final String taskName;
		private final int totalWork;
		private volatile boolean display;
		private Future<?> timerFuture;
		private boolean output;
		private int lastWork;
		private int lastPercent;

		Task(String taskName, int totalWork) {
			this.taskName = taskName;
			this.totalWork = totalWork;
			this.display = true;
		}

		void delay(long time, TimeUnit unit) {
			display = false;
			timerFuture = WorkQueue.getExecutor().schedule(this, time, unit);
		}

		@Override
		public void run() {
			display = true;
		}

		void update(BatchingProgressMonitor pm, int completed) {
			lastWork += completed;

			if(totalWork == UNKNOWN) {
				if(display) {
					pm.onUpdate(taskName, lastWork);
					output = true;
					restartTimer();
				}
			} else {
				int currPercent = lastWork * 100 / totalWork;
				if(display) {
					pm.onUpdate(taskName, lastWork, totalWork, currPercent);
					output = true;
					restartTimer();
					lastPercent = currPercent;
				} else if(currPercent != lastPercent) {
					pm.onUpdate(taskName, lastWork, totalWork, currPercent);
					output = true;
					lastPercent = currPercent;
				}
			}
		}

		private void restartTimer() {
			display = false;
			timerFuture = WorkQueue.getExecutor().schedule(this, 1,
					TimeUnit.SECONDS);
		}

		void end(BatchingProgressMonitor pm) {
			if(output) {
				if(totalWork == UNKNOWN) {
					pm.onEndTask(taskName, lastWork);
				} else {
					int pDone = lastWork * 100 / totalWork;
					pm.onEndTask(taskName, lastWork, totalWork, pDone);
				}
			}
			if(timerFuture != null)
				timerFuture.cancel(false);
		}
	}
}
