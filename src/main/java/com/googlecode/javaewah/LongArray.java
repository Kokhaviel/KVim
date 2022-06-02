package com.googlecode.javaewah;

/*
 * Copyright 2009-2016, Daniel Lemire, Cliff Moon, David McIntosh, Robert Becho, Google Inc., Veronika Zenz, Owen Kaser, Gregory Ssi-Yan-Kai, Rory Graves
 * Licensed under the Apache License, Version 2.0.
 */

import java.util.Arrays;

final class LongArray implements Buffer, Cloneable {

    public LongArray() {
        this(DEFAULT_BUFFER_SIZE);
    }

    public LongArray(int bufferSize) {
        if(bufferSize < 1) {
            bufferSize = 1;
        }
        this.buffer = new long[bufferSize];
    }
    
    @Override
    public int sizeInWords() {
        return this.actualSizeInWords;
    }

    @Override
    public void ensureCapacity(int capacity) {
        resizeBuffer(capacity - this.actualSizeInWords);
    }

    @Override
    public long getWord(int position) {
        return this.buffer[position];
    }
    
    @Override
    public long getLastWord() {
        return getWord(this.actualSizeInWords - 1);
    }

    @Override
    public void clear() {
        this.actualSizeInWords = 1;
        this.buffer[0] = 0;
    }
    
    @Override
    public void trim() {
        this.buffer = Arrays.copyOf(this.buffer, this.actualSizeInWords);
    }
    
    @Override
    public void setWord(int position, long word) {
        this.buffer[position] = word;
    }

	@Override
    public void push_back(long word) {
        resizeBuffer(1);
        this.buffer[this.actualSizeInWords++] = word;
    }

    @Override
    public void push_back(Buffer buffer, int start, int number) {
        resizeBuffer(number);
        if(buffer instanceof LongArray) {
            long[] data = ((LongArray)buffer).buffer;
            System.arraycopy(data, start, this.buffer, this.actualSizeInWords, number);
        } else {
            for(int i = 0; i < number; ++i) {
                this.buffer[this.actualSizeInWords + i] = buffer.getWord(start + i);
            }
        }
        this.actualSizeInWords += number;
    }

    @Override
    public void negative_push_back(Buffer buffer, int start, int number) {
        resizeBuffer(number);
        for (int i = 0; i < number; ++i) {
            this.buffer[this.actualSizeInWords + i] = ~buffer.getWord(start + i);
        }
        this.actualSizeInWords += number;
    }
    
    @Override
    public void removeLastWord() {
        setWord(--this.actualSizeInWords, 0L);
    }

    @Override
    public void andWord(int position, long mask) {
        this.buffer[position] &= mask;
    }

    @Override
    public void orWord(int position, long mask) {
        this.buffer[position] |= mask;
    }
    
    @Override
    public void andLastWord(long mask) {
        andWord(this.actualSizeInWords - 1, mask);
    }
    
    @Override
    public void orLastWord(long mask) {
        orWord(this.actualSizeInWords - 1, mask);
    }
    
    @Override
    public void expand(int position, int length) {
        resizeBuffer(length);
        System.arraycopy(this.buffer, position, this.buffer, position + length, this.actualSizeInWords - position);
        this.actualSizeInWords += length;
    }
    
    @Override
    public void collapse(int position, int length) {
        System.arraycopy(this.buffer, position + length, this.buffer, position, this.actualSizeInWords - position - length);
        for(int i = 0; i < length; ++i) {
            removeLastWord();
        }
    }
    
    @Override
    public LongArray clone() {
        LongArray clone = null;
        try {
            clone = (LongArray) super.clone();
            clone.buffer = this.buffer.clone();
            clone.actualSizeInWords = this.actualSizeInWords;
        } catch (CloneNotSupportedException e) {
            e.printStackTrace();
        }
        return clone;
    }

    private void resizeBuffer(int number) {
        int size = newSizeInWords(number);
        if (size >= this.buffer.length) {
            long[] oldBuffer = this.buffer;
            this.buffer = new long[size];
            System.arraycopy(oldBuffer, 0, this.buffer, 0, oldBuffer.length);
        }
	}

    private int newSizeInWords(int number) {
        int size = this.actualSizeInWords + number;
        if (size >= this.buffer.length) {
            if (size < 32768)
                size = size * 2;
            else if (size * 3 / 2 < size)
                size = Integer.MAX_VALUE;
            else
                size = size * 3 / 2;
        }
        return size;
    }

    private int actualSizeInWords = 1;
    private long[] buffer;
    private static final int DEFAULT_BUFFER_SIZE = 4;
    
}
