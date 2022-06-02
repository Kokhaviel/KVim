package com.googlecode.javaewah;

/*
 * Copyright 2009-2016, Daniel Lemire, Cliff Moon, David McIntosh, Robert Becho, Google Inc., Veronika Zenz, Owen Kaser, Gregory Ssi-Yan-Kai, Rory Graves
 * Licensed under the Apache License, Version 2.0.
 */

public final class IteratingBufferedRunningLengthWord implements IteratingRLW, Cloneable {

	public IteratingBufferedRunningLengthWord(final EWAHIterator iterator) {
		this.iterator = iterator;
		this.brlw = new BufferedRunningLengthWord(this.iterator.next());
		this.literalWordStartPosition = this.iterator.literalWords() + this.brlw.literalWordOffset;
		this.buffer = this.iterator.buffer();
	}

	@Override
	public void discardFirstWords(long x) {
		while(x > 0) {
			if(this.brlw.runningLength > x) {
				this.brlw.runningLength -= x;
				return;
			}
			x -= this.brlw.runningLength;
			this.brlw.runningLength = 0;
			long toDiscard = x > this.brlw.numberOfLiteralWords ? this.brlw.numberOfLiteralWords : x;

			this.literalWordStartPosition += (int) toDiscard;
			this.brlw.numberOfLiteralWords -= toDiscard;
			x -= toDiscard;
			if((x > 0) || (this.brlw.size() == 0)) {
				if(!this.iterator.hasNext()) {
					break;
				}
				this.brlw.reset(this.iterator.next());
				this.literalWordStartPosition = this.iterator.literalWords();
			}
		}
	}

	@Override
	public void discardLiteralWords(long x) {
		this.literalWordStartPosition += x;
		this.brlw.numberOfLiteralWords -= x;
		if(this.brlw.numberOfLiteralWords == 0) {
			if(!this.iterator.hasNext()) {
				return;
			}
			this.brlw.reset(this.iterator.next());
			this.literalWordStartPosition = this.iterator.literalWords();
		}
	}

	@Override
	public void discardRunningWords() {
		this.brlw.runningLength = 0;
		if(this.brlw.getNumberOfLiteralWords() == 0)
			this.next();
	}

	@Override
	public boolean next() {
		if(!this.iterator.hasNext()) {
			this.brlw.numberOfLiteralWords = 0;
			this.brlw.runningLength = 0;
			return false;
		}
		this.brlw.reset(this.iterator.next());
		this.literalWordStartPosition = this.iterator.literalWords();
		return true;
	}

	public long discharge(BitmapStorage container, long max) {
		long index = 0;
		do {
			if(index + getRunningLength() > max) {
				final int offset = (int) (max - index);
				container.addStreamOfEmptyWords(getRunningBit(), offset);
				this.brlw.runningLength -= offset;
				return max;
			}
			container.addStreamOfEmptyWords(getRunningBit(), getRunningLength());
			index += getRunningLength();
			if(getNumberOfLiteralWords() + index > max) {
				final int offset = (int) (max - index);
				writeLiteralWords(offset, container);
				this.brlw.runningLength = 0;
				this.brlw.numberOfLiteralWords -= offset;
				this.literalWordStartPosition += offset;
				return max;
			}
			writeLiteralWords(getNumberOfLiteralWords(), container);
			index += getNumberOfLiteralWords();
		} while(next());
		return index;
	}

	public long dischargeNegated(BitmapStorage container, long max) {
		long index = 0;
		while((index < max) && (size() > 0)) {
			long pl = getRunningLength();
			if(index + pl > max) {
				pl = max - index;
			}
			container.addStreamOfEmptyWords(!getRunningBit(), pl);
			index += pl;
			int pd = getNumberOfLiteralWords();
			if(pd + index > max) {
				pd = (int) (max - index);
			}
			writeNegatedLiteralWords(pd, container);
			discardFirstWords(pl + pd);
			index += pd;
		}
		return index;
	}

	public void discharge(BitmapStorage container) {
		this.brlw.literalWordOffset = this.literalWordStartPosition - this.iterator.literalWords();
		discharge(this.brlw, this.iterator, container);
	}

	@Override
	public long getLiteralWordAt(int index) {
		return this.buffer.getWord(this.literalWordStartPosition + index);
	}

	@Override
	public int getNumberOfLiteralWords() {
		return this.brlw.numberOfLiteralWords;
	}

	@Override
	public boolean getRunningBit() {
		return this.brlw.runningBit;
	}

	@Override
	public long getRunningLength() {
		return this.brlw.runningLength;
	}

	@Override
	public long size() {
		return this.brlw.size();
	}

	public void writeLiteralWords(int numWords, BitmapStorage container) {
		container.addStreamOfLiteralWords(this.buffer, this.literalWordStartPosition, numWords);
	}

	public void writeNegatedLiteralWords(int numWords, BitmapStorage container) {
		container.addStreamOfNegatedLiteralWords(this.buffer, this.literalWordStartPosition, numWords);
	}

	private static void discharge(final BufferedRunningLengthWord initialWord,
								  final EWAHIterator iterator, final BitmapStorage container) {
		BufferedRunningLengthWord runningLengthWord = initialWord;
		for(; ; ) {
			final long runningLength = runningLengthWord.getRunningLength();
			container.addStreamOfEmptyWords(runningLengthWord.getRunningBit(), runningLength);
			container.addStreamOfLiteralWords(iterator.buffer(),
					iterator.literalWords() + runningLengthWord.literalWordOffset,
					runningLengthWord.getNumberOfLiteralWords());
			if(!iterator.hasNext()) break;
			runningLengthWord = new BufferedRunningLengthWord(iterator.next());
		}
	}

	@Override
	public IteratingBufferedRunningLengthWord clone() throws CloneNotSupportedException {
		IteratingBufferedRunningLengthWord answer = (IteratingBufferedRunningLengthWord) super.clone();
		answer.brlw = this.brlw.clone();
		answer.iterator = this.iterator.clone();
		return answer;
	}

	private BufferedRunningLengthWord brlw;
	private final Buffer buffer;
	private int literalWordStartPosition;
	private EWAHIterator iterator;

}
