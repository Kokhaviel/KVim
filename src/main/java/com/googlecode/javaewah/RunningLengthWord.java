package com.googlecode.javaewah;

/*
 * Copyright 2009-2016, Daniel Lemire, Cliff Moon, David McIntosh, Robert Becho, Google Inc., Veronika Zenz, Owen Kaser, Gregory Ssi-Yan-Kai, Rory Graves
 * Licensed under the Apache License, Version 2.0.
 */

public final class RunningLengthWord implements Cloneable {

	RunningLengthWord(final Buffer buffer, final int p) {
		this.buffer = buffer;
		this.position = p;
	}

	public int getNumberOfLiteralWords() {
		return getNumberOfLiteralWords(this.buffer, this.position);
	}

	static int getNumberOfLiteralWords(final Buffer buffer, final int position) {
		return (int) (buffer.getWord(position) >>> (1 + RUNNING_LENGTH_BITS));
	}

	public boolean getRunningBit() {
		return getRunningBit(this.buffer, this.position);
	}

	static boolean getRunningBit(final Buffer buffer, final int position) {
		return (buffer.getWord(position) & 1) != 0;
	}

	public long getRunningLength() {
		return getRunningLength(this.buffer, this.position);
	}

	static long getRunningLength(final Buffer buffer, final int position) {
		return (buffer.getWord(position) >>> 1) & LARGEST_RUNNING_LENGTH_COUNT;
	}

	public void setNumberOfLiteralWords(final long number) {
		setNumberOfLiteralWords(this.buffer, this.position, number);
	}

	static void setNumberOfLiteralWords(final Buffer buffer, final int position, final long number) {
		buffer.orWord(position, NOT_RUNNING_LENGTH_PLUS_RUNNING_BIT);
		buffer.andWord(position, (number << (RUNNING_LENGTH_BITS + 1)) | RUNNING_LENGTH_PLUS_RUNNING_BIT);
	}

	public void setRunningBit(final boolean b) {
		setRunningBit(this.buffer, this.position, b);
	}

	static void setRunningBit(final Buffer buffer, final int position, final boolean b) {
		if(b)
			buffer.orWord(position, 1L);
		else
			buffer.andWord(position, ~1L);
	}

	public void setRunningLength(final long number) {
		setRunningLength(this.buffer, this.position, number);
	}

	static void setRunningLength(final Buffer buffer, final int position, final long number) {
		buffer.orWord(position, SHIFTED_LARGEST_RUNNING_LENGTH_COUNT);
		buffer.andWord(position, (number << 1) | NOT_SHIFTED_LARGEST_RUNNING_LENGTH_COUNT);
	}

	public long size() {
		return getRunningLength() + getNumberOfLiteralWords();
	}

	@Override
	public String toString() {
		return "running bit = " + getRunningBit() + " running length = " + getRunningLength()
				+ " number of lit. words " + getNumberOfLiteralWords();
	}

	@Override
	public RunningLengthWord clone() throws CloneNotSupportedException {
		return (RunningLengthWord) super.clone();
	}

	final Buffer buffer;
	int position;

	public static final int RUNNING_LENGTH_BITS = 32;
	private static final int LITERAL_BITS = 64 - 1 - RUNNING_LENGTH_BITS;
	public static final int LARGEST_LITERAL_COUNT = (1 << LITERAL_BITS) - 1;
	public static final long LARGEST_RUNNING_LENGTH_COUNT = (1L << RUNNING_LENGTH_BITS) - 1;
	private static final long RUNNING_LENGTH_PLUS_RUNNING_BIT = (1L << (RUNNING_LENGTH_BITS + 1)) - 1;
	private static final long SHIFTED_LARGEST_RUNNING_LENGTH_COUNT = LARGEST_RUNNING_LENGTH_COUNT << 1;
	private static final long NOT_RUNNING_LENGTH_PLUS_RUNNING_BIT = ~RUNNING_LENGTH_PLUS_RUNNING_BIT;
	private static final long NOT_SHIFTED_LARGEST_RUNNING_LENGTH_COUNT = ~SHIFTED_LARGEST_RUNNING_LENGTH_COUNT;

}
