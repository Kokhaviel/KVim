package com.googlecode.javaewah32;

/*
 * Copyright 2009-2016, Daniel Lemire, Cliff Moon, David McIntosh, Robert Becho, Google Inc., Veronika Zenz, Owen Kaser, Gregory Ssi-Yan-Kai, Rory Graves
 * Licensed under the Apache License, Version 2.0.
 */

public final class RunningLengthWord32 implements Cloneable {

	RunningLengthWord32(final Buffer32 buffer, final int p) {
		this.buffer = buffer;
		this.position = p;
	}

	public int getNumberOfLiteralWords() {
		return getNumberOfLiteralWords(this.buffer, this.position);
	}

	static int getNumberOfLiteralWords(final Buffer32 buffer, final int position) {
		return (buffer.getWord(position) >>> (1 + RUNNING_LENGTH_BITS));
	}

	public boolean getRunningBit() {
		return getRunningBit(this.buffer, this.position);
	}

	static boolean getRunningBit(final Buffer32 buffer, final int position) {
		return (buffer.getWord(position) & 1) != 0;
	}

	public int getRunningLength() {
		return getRunningLength(this.buffer, this.position);
	}

	static int getRunningLength(final Buffer32 buffer, final int position) {
		return (buffer.getWord(position) >>> 1) & LARGEST_RUNNING_LENGTH_COUNT;
	}

	public void setNumberOfLiteralWords(final int number) {
		setNumberOfLiteralWords(this.buffer, this.position, number);
	}

	static void setNumberOfLiteralWords(final Buffer32 buffer, final int position, final int number) {
		buffer.orWord(position, NOT_RUNNING_LENGTH_PLUS_RUNNING_BIT);
		buffer.andWord(position, (number << (RUNNING_LENGTH_BITS + 1)) | RUNNING_LENGTH_PLUS_RUNNING_BIT);
	}

	public void setRunningBit(final boolean b) {
		setRunningBit(this.buffer, this.position, b);
	}

	static void setRunningBit(final Buffer32 buffer, final int position, final boolean b) {
		if(b)
			buffer.orWord(position, 1);
		else
			buffer.andWord(position, ~1);
	}

	public void setRunningLength(final int number) {
		setRunningLength(this.buffer, this.position, number);
	}

	static void setRunningLength(final Buffer32 buffer, final int position, final int number) {
		buffer.orWord(position, SHIFTED_LARGEST_RUNNING_LENGTH_COUNT);
		buffer.andWord(position, (number << 1) | NOT_SHIFTED_LARGEST_RUNNING_LENGTH_COUNT);
	}

	public int size() {
		return getRunningLength() + getNumberOfLiteralWords();
	}

	@Override
	public String toString() {
		return "running bit = " + getRunningBit()
				+ " running length = " + getRunningLength() + " number of lit. words " + getNumberOfLiteralWords();
	}

	@Override
	public RunningLengthWord32 clone() throws CloneNotSupportedException {
		return (RunningLengthWord32) super.clone();
	}

	final Buffer32 buffer;
	int position;

	public static final int RUNNING_LENGTH_BITS = 16;
	private static final int LITERAL_BITS = 32 - 1 - RUNNING_LENGTH_BITS;
	public static final int LARGEST_LITERAL_COUNT = (1 << LITERAL_BITS) - 1;
	public static final int LARGEST_RUNNING_LENGTH_COUNT = (1 << RUNNING_LENGTH_BITS) - 1;
	private static final int RUNNING_LENGTH_PLUS_RUNNING_BIT = (1 << (RUNNING_LENGTH_BITS + 1)) - 1;
	private static final int SHIFTED_LARGEST_RUNNING_LENGTH_COUNT = LARGEST_RUNNING_LENGTH_COUNT << 1;
	private static final int NOT_RUNNING_LENGTH_PLUS_RUNNING_BIT = ~RUNNING_LENGTH_PLUS_RUNNING_BIT;
	private static final int NOT_SHIFTED_LARGEST_RUNNING_LENGTH_COUNT = ~SHIFTED_LARGEST_RUNNING_LENGTH_COUNT;

}
