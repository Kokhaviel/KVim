/*
 * Copyright (C) 2019, Matthias Sohn <matthias.sohn@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.util;

public class Stats {
	private int n = 0;

	private double avg = 0.0;
	private double min = 0.0;
	private double max = 0.0;
	private double sum = 0.0;

	public void add(double x) {
		n++;
		min = n == 1 ? x : Math.min(min, x);
		max = n == 1 ? x : Math.max(max, x);
		double d = x - avg;
		avg += d / n;
		sum += d * d * (n - 1) / n;
	}

	public double min() {
		if(n < 1) {
			return Double.NaN;
		}
		return min;
	}

	public double max() {
		if(n < 1) {
			return Double.NaN;
		}
		return max;
	}

	public double avg() {
		if(n < 1) {
			return Double.NaN;
		}
		return avg;
	}

	public double var() {
		if(n < 2) {
			return Double.NaN;
		}
		return sum / (n - 1);
	}

	public double stddev() {
		return Math.sqrt(this.var());
	}
}
