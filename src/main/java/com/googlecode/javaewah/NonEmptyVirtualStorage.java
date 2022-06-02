package com.googlecode.javaewah;

/*
 * Copyright 2009-2016, Daniel Lemire, Cliff Moon, David McIntosh, Robert Becho, Google Inc., Veronika Zenz, Owen Kaser, Gregory Ssi-Yan-Kai, Rory Graves
 * Licensed under the Apache License, Version 2.0.
 */

public class NonEmptyVirtualStorage implements BitmapStorage {
    private static final NonEmptyException nonEmptyException = new NonEmptyException();

    @Override
    public void addWord(long newData) {
        if (newData != 0)
            throw nonEmptyException;
    }

    @Override
    public void addStreamOfLiteralWords(Buffer buffer, int start, int number) {
        for(int x = start; x < start + number ; ++x)
           if(buffer.getWord(x)!=0) throw nonEmptyException;
    }

    @Override
    public void addStreamOfEmptyWords(boolean v, long number) {
        if (v && (number > 0))
            throw nonEmptyException;
    }

    @Override
    public void addStreamOfNegatedLiteralWords(Buffer buffer, int start,
                                               int number) {
        if (number > 0) {
            throw nonEmptyException;
        }
    }

    @Override
    public void clear() {
    }

    @Override
    public void setSizeInBitsWithinLastWord(int bits) {
    }

    static class NonEmptyException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }
}
