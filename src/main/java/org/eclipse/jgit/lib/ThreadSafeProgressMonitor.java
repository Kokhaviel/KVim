/*
 * Copyright (C) 2010, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.lib;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public class ThreadSafeProgressMonitor implements ProgressMonitor {

	private final ProgressMonitor pm;
	private final ReentrantLock lock;
	private final Thread mainThread;
	private final AtomicInteger workers;
	private final AtomicInteger pendingUpdates;
	private final Semaphore process;

	public ThreadSafeProgressMonitor(ProgressMonitor pm) {
		this.pm = pm;
		this.lock = new ReentrantLock();
		this.mainThread = Thread.currentThread();
		this.workers = new AtomicInteger(0);
		this.pendingUpdates = new AtomicInteger(0);
		this.process = new Semaphore(0);
	}

	@Override
	public void start(int totalTasks) {
		if(!isMainThread())
			throw new IllegalStateException();
		pm.start(totalTasks);
	}

	@Override
	public void beginTask(String title, int totalWork) {
		if(!isMainThread())
			throw new IllegalStateException();
		pm.beginTask(title, totalWork);
	}

	public void startWorkers(int count) {
		workers.addAndGet(count);
	}

	public void endWorker() {
		if(workers.decrementAndGet() == 0)
			process.release();
	}

	public void waitForCompletion() throws InterruptedException {
		assert isMainThread();
		while(0 < workers.get()) {
			doUpdates();
			process.acquire();
		}
		doUpdates();
	}

	private void doUpdates() {
		int cnt = pendingUpdates.getAndSet(0);
		if(0 < cnt)
			pm.update(cnt);
	}

	@Override
	public void update(int completed) {
		if(0 == pendingUpdates.getAndAdd(completed))
			process.release();
	}

	@Override
	public boolean isCancelled() {
		lock.lock();
		try {
			return pm.isCancelled();
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void endTask() {
		if(!isMainThread())
			throw new IllegalStateException();
		pm.endTask();
	}

	private boolean isMainThread() {
		return Thread.currentThread() == mainThread;
	}
}
