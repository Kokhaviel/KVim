/*
 * Copyright (C) 2016, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.util.time;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.util.concurrent.TimeUnit.MICROSECONDS;

public abstract class ProposedTimestamp implements AutoCloseable {

	public static void blockUntil(Iterable<ProposedTimestamp> times, Duration maxWait) throws TimeoutException, InterruptedException {
		Iterator<ProposedTimestamp> itr = times.iterator();
		if(!itr.hasNext()) {
			return;
		}

		long now = System.currentTimeMillis();
		long deadline = now + maxWait.toMillis();
		for(; ; ) {
			long w = deadline - now;
			if(w < 0) {
				throw new TimeoutException();
			}
			itr.next().blockUntil(Duration.ofMillis(w));
			if(itr.hasNext()) {
				now = System.currentTimeMillis();
			} else {
				break;
			}
		}
	}

	public abstract long read(TimeUnit unit);

	public abstract void blockUntil(Duration maxWait) throws InterruptedException, TimeoutException;

	public long micros() {
		return read(MICROSECONDS);
	}

	public Instant instant() {
		long usec = micros();
		long secs = usec / 1000000L;
		long nanos = (usec % 1000000L) * 1000L;
		return Instant.ofEpochSecond(secs, nanos);
	}

	@Override
	public String toString() {
		return instant().toString();
	}
}
