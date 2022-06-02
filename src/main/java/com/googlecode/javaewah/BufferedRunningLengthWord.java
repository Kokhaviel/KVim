package com.googlecode.javaewah;

/*
 * Copyright 2009-2016, Daniel Lemire, Cliff Moon, David McIntosh, Robert Becho, Google Inc., Veronika Zenz, Owen Kaser, Gregory Ssi-Yan-Kai, Rory Graves
 * Licensed under the Apache License, Version 2.0.
 */

public final class BufferedRunningLengthWord implements Cloneable {

    public BufferedRunningLengthWord(final long a) {
        this.numberOfLiteralWords = (int) (a >>> (1 + RunningLengthWord.RUNNING_LENGTH_BITS));
        this.runningBit = (a & 1) != 0;
        this.runningLength = (int) ((a >>> 1) & RunningLengthWord.LARGEST_RUNNING_LENGTH_COUNT);
    }

    public BufferedRunningLengthWord(final RunningLengthWord rlw) {
        this(rlw.buffer.getWord(rlw.position));
    }

    public int getNumberOfLiteralWords() {
        return this.numberOfLiteralWords;
    }

    public boolean getRunningBit() {
        return this.runningBit;
    }

    public long getRunningLength() {
        return this.runningLength;
    }

    public void reset(final long a) {
        this.numberOfLiteralWords = (int) (a >>> (1 + RunningLengthWord.RUNNING_LENGTH_BITS));
        this.runningBit = (a & 1) != 0;
        this.runningLength = (int) ((a >>> 1) & RunningLengthWord.LARGEST_RUNNING_LENGTH_COUNT);
        this.literalWordOffset = 0;
    }

    public void reset(final RunningLengthWord rlw) {
        reset(rlw.buffer.getWord(rlw.position));
    }

    public long size() {
        return this.runningLength + this.numberOfLiteralWords;
    }

    @Override
    public String toString() {
        return "running bit = " + getRunningBit() + " running length = " + getRunningLength() + " number of lit. words " + getNumberOfLiteralWords();
    }

    @Override
    public BufferedRunningLengthWord clone() throws CloneNotSupportedException {
        BufferedRunningLengthWord answer = (BufferedRunningLengthWord) super.clone();
        answer.literalWordOffset = this.literalWordOffset;
        answer.numberOfLiteralWords = this.numberOfLiteralWords;
        answer.runningBit = this.runningBit;
        answer.runningLength = this.runningLength;
        return answer;
    }

    int literalWordOffset = 0;
    int numberOfLiteralWords;
    boolean runningBit;
    long runningLength;
}
