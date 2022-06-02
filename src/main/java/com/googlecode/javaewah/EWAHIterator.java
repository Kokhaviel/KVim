package com.googlecode.javaewah;

/*
 * Copyright 2009-2016, Daniel Lemire, Cliff Moon, David McIntosh, Robert Becho, Google Inc., Veronika Zenz, Owen Kaser, Gregory Ssi-Yan-Kai, Rory Graves
 * Licensed under the Apache License, Version 2.0.
 */

public final class EWAHIterator implements Cloneable {

	public EWAHIterator(final Buffer buffer) {
		this.rlw = new RunningLengthWord(buffer, 0);
		this.size = buffer.sizeInWords();
		this.pointer = 0;
	}

	private EWAHIterator(int pointer, RunningLengthWord rlw, int size) {
		this.pointer = pointer;
		this.rlw = rlw;
		this.size = size;
	}

	public Buffer buffer() {
		return this.rlw.buffer;
	}

	public int literalWords() {
		return this.pointer - this.rlw.getNumberOfLiteralWords();
	}

	public boolean hasNext() {
		return this.pointer < this.size;
	}

	public RunningLengthWord next() {
		this.rlw.position = this.pointer;
		this.pointer += this.rlw.getNumberOfLiteralWords() + 1;
		return this.rlw;
	}

	@Override
	public EWAHIterator clone() throws CloneNotSupportedException {
		return new EWAHIterator(pointer, rlw.clone(), size);
	}

	private int pointer;
	final RunningLengthWord rlw;
	private final int size;

}
