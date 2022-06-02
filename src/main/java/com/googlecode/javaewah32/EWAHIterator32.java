package com.googlecode.javaewah32;


/*
 * Copyright 2009-2016, Daniel Lemire, Cliff Moon, David McIntosh, Robert Becho, Google Inc., Veronika Zenz, Owen Kaser, Gregory Ssi-Yan-Kai, Rory Graves
 * Licensed under the Apache License, Version 2.0.
 */

public final class EWAHIterator32 implements Cloneable {

    public EWAHIterator32(final Buffer32 buffer) {
        this.rlw = new RunningLengthWord32(buffer, 0);
        this.size = buffer.sizeInWords();
        this.pointer = 0;
    }

    private EWAHIterator32(int pointer, RunningLengthWord32 rlw, int size){
    	this.pointer = pointer;
    	this.rlw = rlw;
    	this.size = size;    	
    }

    public Buffer32 buffer() {
        return this.rlw.buffer;
    }

    public int literalWords() {
        return this.pointer - this.rlw.getNumberOfLiteralWords();
    }

    public boolean hasNext() {
        return this.pointer < this.size;
    }

    public RunningLengthWord32 next() {
        this.rlw.position = this.pointer;
        this.pointer += this.rlw.getNumberOfLiteralWords() + 1;
        return this.rlw;
    }

    @Override
    public EWAHIterator32 clone() throws CloneNotSupportedException {
        return new EWAHIterator32(pointer,rlw.clone(),size);
    }

    private int pointer;

    final RunningLengthWord32 rlw;

    private final int size;

}
