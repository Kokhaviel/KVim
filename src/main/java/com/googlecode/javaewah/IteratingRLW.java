package com.googlecode.javaewah;

/*
 * Copyright 2009-2016, Daniel Lemire, Cliff Moon, David McIntosh, Robert Becho, Google Inc., Veronika Zenz, Owen Kaser, Gregory Ssi-Yan-Kai, Rory Graves
 * Licensed under the Apache License, Version 2.0.
 */

public interface IteratingRLW {

    boolean next();

    long getLiteralWordAt(int index);

    int getNumberOfLiteralWords();

    boolean getRunningBit();

    long size();

    long getRunningLength();

    void discardFirstWords(long x);

    void discardRunningWords();

    void discardLiteralWords(long x);

    IteratingRLW clone() throws CloneNotSupportedException;
}
