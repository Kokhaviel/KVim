package com.googlecode.javaewah32;

/*
 * Copyright 2009-2016, Daniel Lemire, Cliff Moon, David McIntosh, Robert Becho, Google Inc., Veronika Zenz, Owen Kaser, Gregory Ssi-Yan-Kai, Rory Graves
 * Licensed under the Apache License, Version 2.0.
 */

interface Buffer32 {

    int sizeInWords();

    void ensureCapacity(int capacity);

    int getWord(int position);

    int getLastWord();

    void clear();

    void trim();

    void setWord(int position, int word);

    void push_back(int word);

    void push_back(Buffer32 buffer, int start, int number);

    void negative_push_back(Buffer32 buffer, int start, int number);

    void removeLastWord();

    void andWord(int position, int mask);

    void orWord(int position, int mask);

    void andLastWord(int mask);

    void orLastWord(int mask);

    void expand(int position, int length);

    void collapse(int position, int length);

    Buffer32 clone() throws CloneNotSupportedException;
}
