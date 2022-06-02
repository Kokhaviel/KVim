package com.googlecode.javaewah32;

/*
 * Copyright 2009-2016, Daniel Lemire, Cliff Moon, David McIntosh, Robert Becho, Google Inc., Veronika Zenz, Owen Kaser, Gregory Ssi-Yan-Kai, Rory Graves
 * Licensed under the Apache License, Version 2.0.
 */

public interface BitmapStorage32 {

	void addWord(final int newData);

	void addStreamOfLiteralWords(final Buffer32 buffer, final int start, final int number);

	void addStreamOfEmptyWords(final boolean v, final int number);

	void addStreamOfNegatedLiteralWords(final Buffer32 buffer, final int start, final int number);

	void clear();

	void setSizeInBitsWithinLastWord(final int size);
}
