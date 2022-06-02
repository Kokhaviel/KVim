package org.slf4j.helpers;

public class NormalizedParameters {

	public static Throwable getThrowableCandidate(final Object[] argArray) {
		if(argArray == null || argArray.length == 0) {
			return null;
		}

		final Object lastEntry = argArray[argArray.length - 1];
		if(lastEntry instanceof Throwable) {
			return (Throwable) lastEntry;
		}

		return null;
	}

	public static Object[] trimmedCopy(final Object[] argArray) {
		if(argArray == null || argArray.length == 0) {
			throw new IllegalStateException("non-sensical empty or null argument array");
		}

		final int trimmedLen = argArray.length - 1;

		Object[] trimmed = new Object[trimmedLen];

		if(trimmedLen > 0) {
			System.arraycopy(argArray, 0, trimmed, 0, trimmedLen);
		}

		return trimmed;
	}
}
