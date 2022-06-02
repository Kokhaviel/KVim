package com.googlecode.javaewah;

/*
 * Copyright 2009-2016, Daniel Lemire, Cliff Moon, David McIntosh, Robert Becho, Google Inc., Veronika Zenz, Owen Kaser, Gregory Ssi-Yan-Kai, Rory Graves
 * Licensed under the Apache License, Version 2.0.
 */

interface Buffer {

    int sizeInWords();

    void ensureCapacity(int capacity);

    long getWord(int position);

    long getLastWord();

    void clear();

    void trim();

    void setWord(int position, long word);

    void push_back(long word);

    void push_back(Buffer buffer, int start, int number);

    void negative_push_back(Buffer buffer, int start, int number);

    void removeLastWord();

    void andWord(int position, long mask);

    void orWord(int position, long mask);

    void andLastWord(long mask);

    void orLastWord(long mask);

    void expand(int position, int length);

    void collapse(int position, int length);

    Buffer clone() throws CloneNotSupportedException;

}
