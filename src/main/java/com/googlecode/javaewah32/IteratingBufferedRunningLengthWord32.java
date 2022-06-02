package com.googlecode.javaewah32;

/*
 * Copyright 2009-2016, Daniel Lemire, Cliff Moon, David McIntosh, Robert Becho, Google Inc., Veronika Zenz, Owen Kaser, Gregory Ssi-Yan-Kai, Rory Graves
 * Licensed under the Apache License, Version 2.0.
 */

public final class IteratingBufferedRunningLengthWord32 implements IteratingRLW32, Cloneable {

	public IteratingBufferedRunningLengthWord32(
			final EWAHIterator32 iterator) {
		this.iterator = iterator;
		this.brlw = new BufferedRunningLengthWord32(
				this.iterator.next());
		this.literalWordStartPosition = this.iterator.literalWords()
				+ this.brlw.literalWordOffset;
		this.buffer = this.iterator.buffer();
	}

	@Override
	public void discardFirstWords(int x) {
		while(x > 0) {
			if(this.brlw.RunningLength > x) {
				this.brlw.RunningLength -= x;
				return;
			}
			x -= this.brlw.RunningLength;
			this.brlw.RunningLength = 0;
			int toDiscard = Math.min(x, this.brlw.NumberOfLiteralWords);

			this.literalWordStartPosition += toDiscard;
			this.brlw.NumberOfLiteralWords -= toDiscard;
			x -= toDiscard;
			if((x > 0) || (this.brlw.size() == 0)) {
				if(!this.iterator.hasNext()) {
					break;
				}
				this.brlw.reset(this.iterator.next());
				this.literalWordStartPosition = this.iterator
						.literalWords();
			}
		}
	}

	@Override
	public void discardLiteralWords(int x) {
		this.literalWordStartPosition += x;
		this.brlw.NumberOfLiteralWords -= x;
		if(this.brlw.NumberOfLiteralWords == 0) {
			if(!this.iterator.hasNext()) {
				return;
			}
			this.brlw.reset(this.iterator.next());
			this.literalWordStartPosition = this.iterator.literalWords();
		}
	}


	@Override
	public void discardRunningWords() {
		this.brlw.RunningLength = 0;
		if(this.brlw.getNumberOfLiteralWords() == 0)
			this.next();
	}

	public int discharge(BitmapStorage32 container, int max) {
		int index = 0;
		do {
			if(index + getRunningLength() > max) {
				final int offset = max - index;
				container.addStreamOfEmptyWords(getRunningBit(), offset);
				this.brlw.RunningLength -= offset;
				return max;
			}
			container.addStreamOfEmptyWords(getRunningBit(), getRunningLength());
			index += getRunningLength();
			if(getNumberOfLiteralWords() + index > max) {
				final int offset = max - index;
				writeLiteralWords(offset, container);
				this.brlw.RunningLength = 0;
				this.brlw.NumberOfLiteralWords -= offset;
				this.literalWordStartPosition += offset;
				return max;
			}
			writeLiteralWords(getNumberOfLiteralWords(), container);
			index += getNumberOfLiteralWords();
		} while(next());
		return index;
	}


	public int dischargeNegated(BitmapStorage32 container, int max) {
		int index = 0;
		while((index < max) && (size() > 0)) {
			int pl = getRunningLength();
			if(index + pl > max) {
				pl = max - index;
			}
			container.addStreamOfEmptyWords(!getRunningBit(), pl);
			index += pl;
			int pd = getNumberOfLiteralWords();
			if(pd + index > max) {
				pd = max - index;
			}
			writeNegatedLiteralWords(pd, container);
			discardFirstWords(pl + pd);
			index += pd;
		}
		return index;
	}

	@Override
	public boolean next() {
		if(!this.iterator.hasNext()) {
			this.brlw.NumberOfLiteralWords = 0;
			this.brlw.RunningLength = 0;
			return false;
		}
		this.brlw.reset(this.iterator.next());
		this.literalWordStartPosition = this.iterator.literalWords();
		return true;
	}

	public void discharge(BitmapStorage32 container) {
		this.brlw.literalWordOffset = this.literalWordStartPosition - this.iterator.literalWords();
		discharge(this.brlw, this.iterator, container);
	}

	@Override
	public int getLiteralWordAt(int index) {
		return this.buffer.getWord(this.literalWordStartPosition + index);
	}

	@Override
	public int getNumberOfLiteralWords() {
		return this.brlw.NumberOfLiteralWords;
	}

	@Override
	public boolean getRunningBit() {
		return this.brlw.RunningBit;
	}

	@Override
	public int getRunningLength() {
		return this.brlw.RunningLength;
	}

	@Override
	public int size() {
		return this.brlw.size();
	}

	public void writeLiteralWords(int numWords, BitmapStorage32 container) {
		container.addStreamOfLiteralWords(this.buffer,
				this.literalWordStartPosition, numWords);
	}

	public void writeNegatedLiteralWords(int numWords,
										 BitmapStorage32 container) {
		container.addStreamOfNegatedLiteralWords(this.buffer, this.literalWordStartPosition, numWords);
	}

	private static void discharge(
			final BufferedRunningLengthWord32 initialWord,
			final EWAHIterator32 iterator, final BitmapStorage32 container) {
		BufferedRunningLengthWord32 runningLengthWord = initialWord;
		for(; ; ) {
			final int runningLength = runningLengthWord.getRunningLength();
			container.addStreamOfEmptyWords(runningLengthWord.getRunningBit(),
					runningLength);
			container.addStreamOfLiteralWords(iterator.buffer(),
					iterator.literalWords() + runningLengthWord.literalWordOffset,
					runningLengthWord.getNumberOfLiteralWords()
			);
			if(!iterator.hasNext())
				break;
			runningLengthWord = new BufferedRunningLengthWord32(iterator.next());
		}
	}

	@Override
	public IteratingBufferedRunningLengthWord32 clone()
			throws CloneNotSupportedException {
		IteratingBufferedRunningLengthWord32 answer = (IteratingBufferedRunningLengthWord32) super
				.clone();
		answer.brlw = this.brlw.clone();
		answer.iterator = this.iterator.clone();
		return answer;
	}

	private BufferedRunningLengthWord32 brlw;
	private final Buffer32 buffer;
	private int literalWordStartPosition;
	private EWAHIterator32 iterator;


}
