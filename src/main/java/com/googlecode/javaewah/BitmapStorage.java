package com.googlecode.javaewah;

/*
 * Copyright 2009-2016, Daniel Lemire, Cliff Moon, David McIntosh, Robert Becho, Google Inc., Veronika Zenz, Owen Kaser, Gregory Ssi-Yan-Kai, Rory Graves
 * Licensed under the Apache License, Version 2.0.
 */

public interface BitmapStorage {

	void addWord(final long newData);

	void addStreamOfLiteralWords(final Buffer buffer, final int start, final int number);

	void addStreamOfEmptyWords(final boolean v, final long number);

	void addStreamOfNegatedLiteralWords(final Buffer buffer, final int start, final int number);

	void clear();

	void setSizeInBitsWithinLastWord(final int size);
}
