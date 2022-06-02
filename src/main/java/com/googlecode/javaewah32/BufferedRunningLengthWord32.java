package com.googlecode.javaewah32;

/*
 * Copyright 2009-2016, Daniel Lemire, Cliff Moon, David McIntosh, Robert Becho, Google Inc., Veronika Zenz, Owen Kaser, Gregory Ssi-Yan-Kai, Rory Graves
 * Licensed under the Apache License, Version 2.0.
 */

public final class BufferedRunningLengthWord32 implements Cloneable {

	public BufferedRunningLengthWord32(final int a) {
		this.NumberOfLiteralWords = (a >>> (1 + RunningLengthWord32.RUNNING_LENGTH_BITS));
		this.RunningBit = (a & 1) != 0;
		this.RunningLength = ((a >>> 1) & RunningLengthWord32.LARGEST_RUNNING_LENGTH_COUNT);
	}

	public BufferedRunningLengthWord32(final RunningLengthWord32 rlw) {
		this(rlw.buffer.getWord(rlw.position));
	}

	public int getNumberOfLiteralWords() {
		return this.NumberOfLiteralWords;
	}

	public boolean getRunningBit() {
		return this.RunningBit;
	}

	public int getRunningLength() {
		return this.RunningLength;
	}

	public void reset(final int a) {
		this.NumberOfLiteralWords = (a >>> (1 + RunningLengthWord32.RUNNING_LENGTH_BITS));
		this.RunningBit = (a & 1) != 0;
		this.RunningLength = ((a >>> 1) & RunningLengthWord32.LARGEST_RUNNING_LENGTH_COUNT);
		this.literalWordOffset = 0;
	}

	public void reset(final RunningLengthWord32 rlw) {
		reset(rlw.buffer.getWord(rlw.position));
	}

	public int size() {
		return this.RunningLength + this.NumberOfLiteralWords;
	}

	@Override
	public String toString() {
		return "running bit = " + getRunningBit() + " running length = " + getRunningLength()
				+ " number of lit. words " + getNumberOfLiteralWords();
	}

	@Override
	public BufferedRunningLengthWord32 clone() throws CloneNotSupportedException {
		BufferedRunningLengthWord32 answer = (BufferedRunningLengthWord32) super.clone();
		answer.literalWordOffset = this.literalWordOffset;
		answer.NumberOfLiteralWords = this.NumberOfLiteralWords;
		answer.RunningBit = this.RunningBit;
		answer.RunningLength = this.RunningLength;
		return answer;
	}

	public int literalWordOffset = 0;
	int NumberOfLiteralWords;
	public boolean RunningBit;
	public int RunningLength;

}
