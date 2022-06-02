package com.googlecode.javaewah32;

/*
 * Copyright 2009-2016, Daniel Lemire, Cliff Moon, David McIntosh, Robert Becho, Google Inc., Veronika Zenz, Owen Kaser, Gregory Ssi-Yan-Kai, Rory Graves
 * Licensed under the Apache License, Version 2.0.
 */

import java.util.Arrays;

class IntArray implements Buffer32, Cloneable {

    public IntArray() {
        this(DEFAULT_BUFFER_SIZE);
    }

    public IntArray(int bufferSize) {
        if(bufferSize < 1) {
            bufferSize = 1;
        }
        this.buffer = new int[bufferSize];
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
    public int getWord(int position) {
        return this.buffer[position];
    }

    @Override
    public int getLastWord() {
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
    public void setWord(int position, int word) {
        this.buffer[position] = word;
    }

	@Override
    public void push_back(int word) {
        resizeBuffer(1);
        this.buffer[this.actualSizeInWords++] = word;
    }

    @Override
    public void push_back(Buffer32 buffer, int start, int number) {
        resizeBuffer(number);
        if(buffer instanceof IntArray) {
            int[] data = ((IntArray)buffer).buffer;
            System.arraycopy(data, start, this.buffer, this.actualSizeInWords, number);
        } else {
            for(int i = 0; i < number; ++i) {
                this.buffer[this.actualSizeInWords + i] = buffer.getWord(start + i);
            }
        }
        this.actualSizeInWords += number;
    }
    
    @Override
    public void negative_push_back(Buffer32 buffer, int start, int number) {
        resizeBuffer(number);
        for (int i = 0; i < number; ++i) {
            this.buffer[this.actualSizeInWords + i] = ~buffer.getWord(start + i);
        }
        this.actualSizeInWords += number;
    }
    
    @Override
    public void removeLastWord() {
        setWord(--this.actualSizeInWords, 0);
    }

    @Override
    public void andWord(int position, int mask) {
        this.buffer[position] &= mask;
    }
    
    @Override
    public void orWord(int position, int mask) {
        this.buffer[position] |= mask;
    }
    
    @Override
    public void andLastWord(int mask) {
        andWord(this.actualSizeInWords - 1, mask);
    }
    
    @Override
    public void orLastWord(int mask) {
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
    public IntArray clone() {
        IntArray clone = null;
        try {
            clone = (IntArray) super.clone();
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
            int[] oldBuffer = this.buffer;
            this.buffer = new int[size];
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
    private int[] buffer;
    private static final int DEFAULT_BUFFER_SIZE = 4;
}
