package com.googlecode.javaewah;

/*
 * Copyright 2009-2016, Daniel Lemire, Cliff Moon, David McIntosh, Robert Becho, Google Inc., Veronika Zenz, Owen Kaser, Gregory Ssi-Yan-Kai, Rory Graves
 * Licensed under the Apache License, Version 2.0.
 */

public interface LogicalElement<T> {

    T and(T le);

    T andNot(T le);

    T or(T le);

    int sizeInBits();

    int sizeInBytes();

    T xor(T le);

}
