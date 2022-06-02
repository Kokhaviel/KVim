package com.googlecode.javaewah32;

/*
 * Copyright 2009-2016, Daniel Lemire, Cliff Moon, David McIntosh, Robert Becho, Google Inc., Veronika Zenz, Owen Kaser, Gregory Ssi-Yan-Kai, Rory Graves
 * Licensed under the Apache License, Version 2.0.
 */

public interface IteratingRLW32 {

    boolean next();

    int getLiteralWordAt(int index);

    int getNumberOfLiteralWords();

    boolean getRunningBit();

    int size();

    int getRunningLength();

    void discardFirstWords(int x);

    void discardRunningWords();

    void discardLiteralWords(int x);

    IteratingRLW32 clone() throws CloneNotSupportedException;
}
