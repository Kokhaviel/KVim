package com.googlecode.javaewah;

/*
 * Copyright 2009-2016, Daniel Lemire, Cliff Moon, David McIntosh, Robert Becho, Google Inc., Veronika Zenz, Owen Kaser, Gregory Ssi-Yan-Kai, Rory Graves
 * Licensed under the Apache License, Version 2.0.
 */

import java.io.*;
import java.util.Iterator;

public final class EWAHCompressedBitmap implements Cloneable, Externalizable,
		Iterable<Integer>, BitmapStorage, LogicalElement<EWAHCompressedBitmap> {

	public EWAHCompressedBitmap() {
		this(new LongArray());
	}

	public EWAHCompressedBitmap(int bufferSize) {
		this(new LongArray(bufferSize));
	}


	private EWAHCompressedBitmap(Buffer buffer) {
		this.buffer = buffer;
		this.rlw = new RunningLengthWord(this.buffer, 0);
	}

	@Override
	public void addWord(final long newData) {
		addWord(newData, WORD_IN_BITS);
	}

	public void addWord(final long newData, final int bitsThatMatter) {
		this.sizeInBits += bitsThatMatter;
		if(newData == 0) {
			insertEmptyWord(false);
		} else if(newData == ~0L) {
			insertEmptyWord(true);
		} else {
			insertLiteralWord(newData);
		}
	}

	private void insertEmptyWord(final boolean v) {
		final boolean noLiteralWords = (this.rlw.getNumberOfLiteralWords() == 0);
		final long runningLength = this.rlw.getRunningLength();
		if(noLiteralWords && runningLength == 0) {
			this.rlw.setRunningBit(v);
		}
		if(noLiteralWords && this.rlw.getRunningBit() == v
				&& (runningLength < RunningLengthWord.LARGEST_RUNNING_LENGTH_COUNT)) {
			this.rlw.setRunningLength(runningLength + 1);
			return;
		}
		this.buffer.push_back(0);
		this.rlw.position = this.buffer.sizeInWords() - 1;
		this.rlw.setRunningBit(v);
		this.rlw.setRunningLength(1);
	}

	private void insertLiteralWord(final long newData) {
		final int numberSoFar = this.rlw.getNumberOfLiteralWords();
		if(numberSoFar >= RunningLengthWord.LARGEST_LITERAL_COUNT) {
			this.buffer.push_back(0);
			this.rlw.position = this.buffer.sizeInWords() - 1;
			this.rlw.setNumberOfLiteralWords(1);
			this.buffer.push_back(newData);
		} else {
			this.rlw.setNumberOfLiteralWords(numberSoFar + 1);
			this.buffer.push_back(newData);
		}
	}

	@Override
	public void addStreamOfLiteralWords(final Buffer buffer, final int start,
										final int number) {
		int leftOverNumber = number;
		while(leftOverNumber > 0) {
			final int numberOfLiteralWords = this.rlw.getNumberOfLiteralWords();
			final int whatWeCanAdd = Math.min(leftOverNumber, RunningLengthWord.LARGEST_LITERAL_COUNT
					- numberOfLiteralWords);
			this.rlw.setNumberOfLiteralWords(numberOfLiteralWords + whatWeCanAdd);
			leftOverNumber -= whatWeCanAdd;
			this.buffer.push_back(buffer, start, whatWeCanAdd);
			this.sizeInBits += whatWeCanAdd * WORD_IN_BITS;
			if(leftOverNumber > 0) {
				this.buffer.push_back(0);
				this.rlw.position = this.buffer.sizeInWords() - 1;
			}
		}
	}

	@Override
	public void addStreamOfEmptyWords(final boolean v, long number) {
		if(number == 0)
			return;
		this.sizeInBits += (int) (number * WORD_IN_BITS);
		fastaddStreamOfEmptyWords(v, number);
	}

	@Override
	public void addStreamOfNegatedLiteralWords(final Buffer buffer,
											   final int start, final int number) {
		int leftOverNumber = number;
		while(leftOverNumber > 0) {
			final int numberOfLiteralWords = this.rlw.getNumberOfLiteralWords();
			final int whatWeCanAdd = Math.min(leftOverNumber, RunningLengthWord.LARGEST_LITERAL_COUNT
					- numberOfLiteralWords);
			this.rlw.setNumberOfLiteralWords(numberOfLiteralWords + whatWeCanAdd);
			leftOverNumber -= whatWeCanAdd;
			this.buffer.negative_push_back(buffer, start, whatWeCanAdd);
			this.sizeInBits += whatWeCanAdd * WORD_IN_BITS;
			if(leftOverNumber > 0) {
				this.buffer.push_back(0);
				this.rlw.position = this.buffer.sizeInWords() - 1;
			}
		}
	}

	@Override
	public EWAHCompressedBitmap and(final EWAHCompressedBitmap a) {
		int size = Math.max(this.buffer.sizeInWords(), a.buffer.sizeInWords());
		final EWAHCompressedBitmap container = new EWAHCompressedBitmap(size);
		andToContainer(a, container);
		return container;
	}

	public void andToContainer(final EWAHCompressedBitmap a, final BitmapStorage container) {
		container.clear();
		final EWAHIterator i = a.getEWAHIterator();
		final EWAHIterator j = getEWAHIterator();
		final IteratingBufferedRunningLengthWord rlwi = new IteratingBufferedRunningLengthWord(i);
		final IteratingBufferedRunningLengthWord rlwj = new IteratingBufferedRunningLengthWord(j);
		while((rlwi.size() > 0) && (rlwj.size() > 0)) {
			while((rlwi.getRunningLength() > 0) || (rlwj.getRunningLength() > 0)) {
				final boolean i_is_prey = rlwi.getRunningLength() < rlwj.getRunningLength();
				final IteratingBufferedRunningLengthWord prey = i_is_prey ? rlwi : rlwj;
				final IteratingBufferedRunningLengthWord predator = i_is_prey ? rlwj : rlwi;
				if(!predator.getRunningBit()) {
					container.addStreamOfEmptyWords(false, predator.getRunningLength());
					prey.discardFirstWords(predator.getRunningLength());
				} else {
					final long index = prey.discharge(container, predator.getRunningLength());
					container.addStreamOfEmptyWords(false, predator.getRunningLength() - index);
				}
				predator.discardRunningWords();
			}
			final int nbre_literal = Math.min(rlwi.getNumberOfLiteralWords(), rlwj.getNumberOfLiteralWords());
			if(nbre_literal > 0) {
				for(int k = 0; k < nbre_literal; ++k) {
					container.addWord(rlwi.getLiteralWordAt(k) & rlwj.getLiteralWordAt(k));
				}
				rlwi.discardLiteralWords(nbre_literal);
				rlwj.discardLiteralWords(nbre_literal);
			}
		}

		if(ADJUST_CONTAINER_SIZE_WHEN_AGGREGATING) {
			container.setSizeInBitsWithinLastWord(Math.max(sizeInBits(), a.sizeInBits()));
		}
	}

	@Override
	public EWAHCompressedBitmap andNot(final EWAHCompressedBitmap a) {
		int size = Math.max(this.buffer.sizeInWords(), a.buffer.sizeInWords());
		final EWAHCompressedBitmap container = new EWAHCompressedBitmap(size);
		andNotToContainer(a, container);
		return container;
	}

	public void andNotToContainer(final EWAHCompressedBitmap a,
								  final BitmapStorage container) {
		container.clear();
		final EWAHIterator i = getEWAHIterator();
		final EWAHIterator j = a.getEWAHIterator();
		final IteratingBufferedRunningLengthWord rlwi = new IteratingBufferedRunningLengthWord(i);
		final IteratingBufferedRunningLengthWord rlwj = new IteratingBufferedRunningLengthWord(j);
		while((rlwi.size() > 0) && (rlwj.size() > 0)) {
			while((rlwi.getRunningLength() > 0) || (rlwj.getRunningLength() > 0)) {
				final boolean i_is_prey = rlwi.getRunningLength() < rlwj.getRunningLength();
				final IteratingBufferedRunningLengthWord prey = i_is_prey ? rlwi : rlwj;
				final IteratingBufferedRunningLengthWord predator = i_is_prey ? rlwj : rlwi;
				if(((predator.getRunningBit()) && (i_is_prey)) || ((!predator.getRunningBit()) && (!i_is_prey))) {
					container.addStreamOfEmptyWords(false, predator.getRunningLength());
					prey.discardFirstWords(predator.getRunningLength());
				} else if(i_is_prey) {
					final long index = prey.discharge(container, predator.getRunningLength());
					container.addStreamOfEmptyWords(false, predator.getRunningLength() - index);
				} else {
					final long index = prey.dischargeNegated(container, predator.getRunningLength());
					container.addStreamOfEmptyWords(true, predator.getRunningLength() - index);
				}
				predator.discardRunningWords();
			}
			final int nbre_literal = Math.min(rlwi.getNumberOfLiteralWords(), rlwj.getNumberOfLiteralWords());
			if(nbre_literal > 0) {
				for(int k = 0; k < nbre_literal; ++k)
					container.addWord(rlwi.getLiteralWordAt(k) & (~rlwj.getLiteralWordAt(k)));
				rlwi.discardLiteralWords(nbre_literal);
				rlwj.discardLiteralWords(nbre_literal);
			}
		}
		final boolean i_remains = rlwi.size() > 0;
		final IteratingBufferedRunningLengthWord remaining = i_remains ? rlwi : rlwj;
		if(i_remains)
			remaining.discharge(container);
		if(ADJUST_CONTAINER_SIZE_WHEN_AGGREGATING)
			container.setSizeInBitsWithinLastWord(Math.max(sizeInBits(),
					a.sizeInBits()));
	}

	public int cardinality() {
		int counter = 0;
		final EWAHIterator i = this.getEWAHIterator();
		while(i.hasNext()) {
			RunningLengthWord localrlw = i.next();
			if(localrlw.getRunningBit()) {
				counter += (int) (WORD_IN_BITS * localrlw.getRunningLength());
			}
			final int numberOfLiteralWords = localrlw.getNumberOfLiteralWords();
			final int literalWords = i.literalWords();
			for(int j = 0; j < numberOfLiteralWords; ++j) {
				counter += Long.bitCount(i.buffer().getWord(literalWords + j));
			}
		}
		return counter;
	}

	@Override
	public void clear() {
		this.sizeInBits = 0;
		this.buffer.clear();
		this.rlw.position = 0;
	}

	@Override
	public EWAHCompressedBitmap clone() throws CloneNotSupportedException {
		EWAHCompressedBitmap clone = new EWAHCompressedBitmap(this.buffer.clone());
		clone.sizeInBits = this.sizeInBits;
		clone.rlw = new RunningLengthWord(clone.buffer, this.rlw.position);
		return clone;
	}

	public void serialize(DataOutput out) throws IOException {
		out.writeInt(this.sizeInBits);
		final int siw = this.buffer.sizeInWords();
		out.writeInt(siw);
		for(int i = 0; i < siw; ++i) {
			out.writeLong(this.buffer.getWord(i));
		}
		out.writeInt(this.rlw.position);
	}

	public void deserialize(DataInput in) throws IOException {
		this.sizeInBits = in.readInt();
		int sizeInWords = in.readInt();
		this.buffer.clear();
		this.buffer.removeLastWord();
		this.buffer.ensureCapacity(sizeInWords);
		for(int i = 0; i < sizeInWords; ++i) {
			this.buffer.push_back(in.readLong());
		}
		this.rlw = new RunningLengthWord(this.buffer, in.readInt());
	}

	@Override
	public boolean equals(Object o) {
		if(o instanceof EWAHCompressedBitmap) {
			try {
				this.xorToContainer((EWAHCompressedBitmap) o,
						new NonEmptyVirtualStorage());
				return true;
			} catch(NonEmptyVirtualStorage.NonEmptyException e) {
				return false;
			}
		}
		return false;
	}

	private void fastaddStreamOfEmptyWords(final boolean v, long number) {
		if((this.rlw.getRunningBit() != v) && (this.rlw.size() == 0)) {
			this.rlw.setRunningBit(v);
		} else if((this.rlw.getNumberOfLiteralWords() != 0) || (this.rlw.getRunningBit() != v)) {
			this.buffer.push_back(0);
			this.rlw.position = this.buffer.sizeInWords() - 1;
			if(v)
				this.rlw.setRunningBit(true);
		}

		final long runLen = this.rlw.getRunningLength();
		final long whatWeCanAdd = Math.min(number, RunningLengthWord.LARGEST_RUNNING_LENGTH_COUNT
				- runLen);
		this.rlw.setRunningLength(runLen + whatWeCanAdd);
		number -= whatWeCanAdd;

		while(number >= RunningLengthWord.LARGEST_RUNNING_LENGTH_COUNT) {
			this.buffer.push_back(0);
			this.rlw.position = this.buffer.sizeInWords() - 1;
			if(v)
				this.rlw.setRunningBit(true);
			this.rlw.setRunningLength(RunningLengthWord.LARGEST_RUNNING_LENGTH_COUNT);
			number -= RunningLengthWord.LARGEST_RUNNING_LENGTH_COUNT;
		}
		if(number > 0) {
			this.buffer.push_back(0);
			this.rlw.position = this.buffer.sizeInWords() - 1;
			if(v)
				this.rlw.setRunningBit(true);
			this.rlw.setRunningLength(number);
		}
	}

	public EWAHIterator getEWAHIterator() {
		return new EWAHIterator(this.buffer);
	}

	@Override
	public int hashCode() {
		int karprabin = 0;
		final int B = 0x9e3779b1;
		final EWAHIterator i = this.getEWAHIterator();
		while(i.hasNext()) {
			i.next();
			if(i.rlw.getRunningBit()) {
				final long rl = i.rlw.getRunningLength();
				karprabin += (int) (B * (rl));
				karprabin += (int) (B * ((rl >>> 32)));
			}
			final int nlw = i.rlw.getNumberOfLiteralWords();
			final int lw = i.literalWords();
			for(int k = 0; k < nlw; ++k) {
				long W = this.buffer.getWord(lw + k);
				karprabin += B * (W);
				karprabin += B * ((W >>> 32));
			}
		}
		return karprabin;
	}

	public IntIterator intIterator() {
		return new IntIteratorImpl(this.getEWAHIterator());
	}

	@Override
	public Iterator<Integer> iterator() {
		return new Iterator<Integer>() {
			@Override
			public boolean hasNext() {
				return this.under.hasNext();
			}

			@Override
			public Integer next() {
				return this.under.next();
			}

			@Override
			public void remove() {
				throw new UnsupportedOperationException(
						"bitsets do not support remove");
			}

			private final IntIterator under = intIterator();
		};
	}

	@Override
	public EWAHCompressedBitmap or(final EWAHCompressedBitmap a) {
		int size = this.buffer.sizeInWords() + a.buffer.sizeInWords();
		final EWAHCompressedBitmap container = new EWAHCompressedBitmap(size);
		orToContainer(a, container);
		return container;
	}

	public void orToContainer(final EWAHCompressedBitmap a,
							  final BitmapStorage container) {
		container.clear();
		final EWAHIterator i = a.getEWAHIterator();
		final EWAHIterator j = getEWAHIterator();
		final IteratingBufferedRunningLengthWord rlwi = new IteratingBufferedRunningLengthWord(i);
		final IteratingBufferedRunningLengthWord rlwj = new IteratingBufferedRunningLengthWord(j);
		while((rlwi.size() > 0) && (rlwj.size() > 0)) {
			while((rlwi.getRunningLength() > 0) || (rlwj.getRunningLength() > 0)) {
				final boolean i_is_prey = rlwi.getRunningLength() < rlwj.getRunningLength();
				final IteratingBufferedRunningLengthWord prey = i_is_prey ? rlwi : rlwj;
				final IteratingBufferedRunningLengthWord predator = i_is_prey ? rlwj : rlwi;
				if(predator.getRunningBit()) {
					container.addStreamOfEmptyWords(true, predator.getRunningLength());
					prey.discardFirstWords(predator.getRunningLength());
				} else {
					final long index = prey.discharge(container, predator.getRunningLength());
					container.addStreamOfEmptyWords(false, predator.getRunningLength() - index
					);
				}
				predator.discardRunningWords();
			}
			final int nbre_literal = Math.min(
					rlwi.getNumberOfLiteralWords(),
					rlwj.getNumberOfLiteralWords());
			if(nbre_literal > 0) {
				for(int k = 0; k < nbre_literal; ++k) {
					container.addWord(rlwi.getLiteralWordAt(k) | rlwj.getLiteralWordAt(k));
				}
				rlwi.discardLiteralWords(nbre_literal);
				rlwj.discardLiteralWords(nbre_literal);
			}
		}
		final boolean i_remains = rlwi.size() > 0;
		final IteratingBufferedRunningLengthWord remaining = i_remains ? rlwi
				: rlwj;
		remaining.discharge(container);
		container.setSizeInBitsWithinLastWord(Math.max(sizeInBits(), a.sizeInBits()));
	}

	@Override
	public void readExternal(ObjectInput in) throws IOException {
		deserialize(in);
	}

	@Override
	public void writeExternal(ObjectOutput out) throws IOException {
		serialize(out);
	}

	public boolean set(final int i) {
		return set(i, true);
	}

	private boolean set(int i, boolean value) {
		if((i > Integer.MAX_VALUE - WORD_IN_BITS) || (i < 0))
			throw new IndexOutOfBoundsException("Position should be between 0 and " + (Integer.MAX_VALUE - WORD_IN_BITS));
		if(i < this.sizeInBits) {
			locateAndSet(i, value);
		} else {
			extendAndSet(i, value);
		}
		return true;
	}

	private void extendAndSet(int i, boolean value) {
		final int dist = distanceInWords(i);
		this.sizeInBits = i + 1;
		if(value) {
			if(dist > 0) {
				if(this.rlw.getNumberOfLiteralWords() > 0 && this.buffer.getLastWord() == 0L) {
					this.buffer.removeLastWord();
					this.rlw.setNumberOfLiteralWords(this.rlw.getNumberOfLiteralWords() - 1);
					insertEmptyWord(false);
				}
				if(dist > 1) {
					fastaddStreamOfEmptyWords(false, dist - 1);
				}
				insertLiteralWord(1L << (i % WORD_IN_BITS));
				return;
			}
			if(this.rlw.getNumberOfLiteralWords() == 0) {
				this.rlw.setRunningLength(this.rlw.getRunningLength() - 1);
				insertLiteralWord(1L << (i % WORD_IN_BITS));
				return;
			}
			this.buffer.orLastWord(1L << (i % WORD_IN_BITS));
			if(this.buffer.getLastWord() == ~0L) {
				this.buffer.removeLastWord();
				this.rlw.setNumberOfLiteralWords(this.rlw.getNumberOfLiteralWords() - 1);
				insertEmptyWord(true);
			}
		} else {
			if(dist > 0) {
				fastaddStreamOfEmptyWords(false, dist);
			}
		}
	}

	private void locateAndSet(int i, boolean value) {
		int nbits = 0;
		final int siw = this.buffer.sizeInWords();
		for(int pos = 0; pos < siw; ) {
			long rl = RunningLengthWord.getRunningLength(this.buffer, pos);
			boolean rb = RunningLengthWord.getRunningBit(this.buffer, pos);
			long lw = RunningLengthWord.getNumberOfLiteralWords(this.buffer, pos);
			long rbits = rl * WORD_IN_BITS;
			if(i < nbits + rbits) {
				setInRunningLength(value, i, nbits, pos, rl, rb, lw);
				return;
			}
			nbits += (int) rbits;
			long lbits = lw * WORD_IN_BITS;
			if(i < nbits + lbits) {
				setInLiteralWords(value, i, nbits, pos, rl, rb, lw);
				return;
			}
			nbits += (int) lbits;
			pos += (int) (lw + 1);
		}
	}

	private void setInRunningLength(boolean value, int i, int nbits, int pos, long rl, boolean rb, long lw) {
		if(value != rb) {
			int wordPosition = (i - nbits) / WORD_IN_BITS + 1;
			int addedWords = (wordPosition == rl) ? 1 : 2;
			this.buffer.expand(pos + 1, addedWords);
			long mask = 1L << i % WORD_IN_BITS;
			this.buffer.setWord(pos + 1, value ? mask : ~mask);
			if(this.rlw.position >= pos + 1) {
				this.rlw.position += addedWords;
			}
			if(addedWords == 1) {
				setRLWInfo(pos, rb, rl - 1, lw + 1);
			} else {
				setRLWInfo(pos, rb, wordPosition - 1, 1L);
				setRLWInfo(pos + 2, rb, rl - wordPosition, lw);
				if(this.rlw.position == pos) {
					this.rlw.position += 2;
				}
			}
		}
	}

	private void setInLiteralWords(boolean value, int i, int nbits, int pos, long rl, boolean rb, long lw) {
		int wordPosition = (i - nbits) / WORD_IN_BITS + 1;
		long mask = 1L << i % WORD_IN_BITS;
		if(value) {
			this.buffer.orWord(pos + wordPosition, mask);
		} else {
			this.buffer.andWord(pos + wordPosition, ~mask);
		}
		long emptyWord = value ? ~0L : 0L;
		if(this.buffer.getWord(pos + wordPosition) == emptyWord) {
			boolean canMergeInCurrentRLW = mergeLiteralWordInCurrentRunningLength(value, rb, rl, wordPosition);
			boolean canMergeInNextRLW = mergeLiteralWordInNextRunningLength(value, lw, pos, wordPosition);
			if(canMergeInCurrentRLW && canMergeInNextRLW) {
				long nextRl = RunningLengthWord.getRunningLength(this.buffer, pos + 2);
				long nextLw = RunningLengthWord.getNumberOfLiteralWords(this.buffer, pos + 2);
				this.buffer.collapse(pos, 2);
				setRLWInfo(pos, value, rl + 1 + nextRl, nextLw);
				if(this.rlw.position >= pos + 2) {
					this.rlw.position -= 2;
				}
			} else if(canMergeInCurrentRLW) {
				this.buffer.collapse(pos + 1, 1);
				setRLWInfo(pos, value, rl + 1, lw - 1);
				if(this.rlw.position >= pos + 2) {
					this.rlw.position--;
				}
			} else if(canMergeInNextRLW) {
				int nextRLWPos = (int) (pos + lw + 1);
				long nextRl = RunningLengthWord.getRunningLength(this.buffer, nextRLWPos);
				long nextLw = RunningLengthWord.getNumberOfLiteralWords(this.buffer, nextRLWPos);
				this.buffer.collapse(pos + wordPosition, 1);
				setRLWInfo(pos, rb, rl, lw - 1);
				setRLWInfo(pos + wordPosition, value, nextRl + 1, nextLw);
				if(this.rlw.position >= nextRLWPos) {
					this.rlw.position -= lw + 1 - wordPosition;
				}
			} else {
				setRLWInfo(pos, rb, rl, wordPosition - 1);
				setRLWInfo(pos + wordPosition, value, 1L, lw - wordPosition);
				if(this.rlw.position == pos) {
					this.rlw.position += wordPosition;
				}
			}
		}
	}

	private boolean mergeLiteralWordInCurrentRunningLength(boolean value, boolean rb, long rl, int wordPosition) {
		return (value == rb || rl == 0) && wordPosition == 1;
	}

	private boolean mergeLiteralWordInNextRunningLength(boolean value, long lw, int pos, int wordPosition) {
		int nextRLWPos = (int) (pos + lw + 1);
		if(lw == wordPosition && nextRLWPos < this.buffer.sizeInWords()) {
			long nextRl = RunningLengthWord.getRunningLength(this.buffer, nextRLWPos);
			boolean nextRb = RunningLengthWord.getRunningBit(this.buffer, nextRLWPos);
			return (value == nextRb || nextRl == 0);
		}
		return false;
	}

	private void setRLWInfo(int pos, boolean rb, long rl, long lw) {
		RunningLengthWord.setRunningBit(this.buffer, pos, rb);
		RunningLengthWord.setRunningLength(this.buffer, pos, rl);
		RunningLengthWord.setNumberOfLiteralWords(this.buffer, pos, lw);
	}

	@Override
	public void setSizeInBitsWithinLastWord(final int size) {
		if((size + WORD_IN_BITS - 1) / WORD_IN_BITS > (this.sizeInBits + WORD_IN_BITS - 1) / WORD_IN_BITS) {
			setSizeInBits(size, false);
			return;
		}
		if((size + WORD_IN_BITS - 1) / WORD_IN_BITS != (this.sizeInBits + WORD_IN_BITS - 1) / WORD_IN_BITS)
			throw new RuntimeException(
					"You can only reduce the size of the bitmap within the scope of the last word. To extend the bitmap, please call setSizeInBits(int,boolean).");
		this.sizeInBits = size;
		final int usedBitsInLast = this.sizeInBits % WORD_IN_BITS;
		if(usedBitsInLast == 0)
			return;
		if(this.rlw.getNumberOfLiteralWords() == 0) {
			if(this.rlw.getRunningLength() > 0) {
				this.rlw.setRunningLength(this.rlw.getRunningLength() - 1);
				final long word = this.rlw.getRunningBit() ? (~0L) >>> (WORD_IN_BITS - usedBitsInLast) : 0L;
				this.insertLiteralWord(word);
			}
			return;
		}
		this.buffer.andLastWord((~0L) >>> (WORD_IN_BITS - usedBitsInLast));
	}

	public boolean setSizeInBits(final int size, final boolean defaultValue) {
		if(size <= this.sizeInBits) {
			return false;
		}
		if((this.sizeInBits % WORD_IN_BITS) != 0) {
			if(!defaultValue) {
				if(this.rlw.getNumberOfLiteralWords() > 0) {
					final int bitsToAdd = size - this.sizeInBits;
					final int usedBitsInLast = this.sizeInBits % WORD_IN_BITS;
					final int freeBitsInLast = WORD_IN_BITS - usedBitsInLast;
					if(this.buffer.getLastWord() == 0L) {
						this.rlw.setNumberOfLiteralWords(this.rlw.getNumberOfLiteralWords() - 1);
						this.buffer.removeLastWord();
						this.sizeInBits -= usedBitsInLast;
					} else if(usedBitsInLast > 0) {
						this.sizeInBits += Math.min(bitsToAdd, freeBitsInLast);
					}
				}
			} else {
				if(this.rlw.getNumberOfLiteralWords() == 0) {
					this.rlw.setRunningLength(this.rlw.getRunningLength() - 1);
					insertLiteralWord(0);
				}
				final int maskWidth = Math.min(WORD_IN_BITS - this.sizeInBits % WORD_IN_BITS,
						size - this.sizeInBits);
				final int maskShift = this.sizeInBits % WORD_IN_BITS;
				final long mask = ((~0L) >>> (WORD_IN_BITS - maskWidth)) << maskShift;
				this.buffer.orLastWord(mask);
				if(this.buffer.getLastWord() == ~0L) {
					this.buffer.removeLastWord();
					this.rlw.setNumberOfLiteralWords(this.rlw.getNumberOfLiteralWords() - 1);
					insertEmptyWord(true);
				}
				this.sizeInBits += maskWidth;
			}
		}
		this.addStreamOfEmptyWords(defaultValue,
				(size / WORD_IN_BITS) - (this.sizeInBits / WORD_IN_BITS)
		);
		if(this.sizeInBits < size) {
			final int dist = distanceInWords(size - 1);
			if(dist > 0) {
				insertLiteralWord(0);
			}
			if(defaultValue) {
				final int maskWidth = size - this.sizeInBits;
				final int maskShift = this.sizeInBits % WORD_IN_BITS;
				final long mask = ((~0L) >>> (WORD_IN_BITS - maskWidth)) << maskShift;
				this.buffer.orLastWord(mask);
			}
			this.sizeInBits = size;
		}
		return true;
	}

	private int distanceInWords(int i) {
		return (i + WORD_IN_BITS) / WORD_IN_BITS
				- (this.sizeInBits + WORD_IN_BITS - 1) / WORD_IN_BITS;
	}

	@Override
	public int sizeInBits() {
		return this.sizeInBits;
	}

	@Override
	public int sizeInBytes() {
		return this.buffer.sizeInWords() * (WORD_IN_BITS / 8);
	}

	@Override
	public String toString() {
		StringBuilder answer = new StringBuilder();
		IntIterator i = this.intIterator();
		answer.append("{");
		if(i.hasNext())
			answer.append(i.next());
		while(i.hasNext()) {
			answer.append(",");
			answer.append(i.next());
		}
		answer.append("}");
		return answer.toString();
	}

	public void trim() {
		this.buffer.trim();
	}

	@Override
	public EWAHCompressedBitmap xor(final EWAHCompressedBitmap a) {
		int size = this.buffer.sizeInWords() + a.buffer.sizeInWords();
		final EWAHCompressedBitmap container = new EWAHCompressedBitmap(size);
		xorToContainer(a, container);
		return container;
	}

	public void xorToContainer(final EWAHCompressedBitmap a,
							   final BitmapStorage container) {
		container.clear();
		final EWAHIterator i = a.getEWAHIterator();
		final EWAHIterator j = getEWAHIterator();
		final IteratingBufferedRunningLengthWord rlwi = new IteratingBufferedRunningLengthWord(i);
		final IteratingBufferedRunningLengthWord rlwj = new IteratingBufferedRunningLengthWord(j);
		while((rlwi.size() > 0) && (rlwj.size() > 0)) {
			while((rlwi.getRunningLength() > 0) || (rlwj.getRunningLength() > 0)) {
				final boolean i_is_prey = rlwi.getRunningLength() < rlwj.getRunningLength();
				final IteratingBufferedRunningLengthWord prey = i_is_prey ? rlwi : rlwj;
				final IteratingBufferedRunningLengthWord predator = i_is_prey ? rlwj : rlwi;
				final long index = (!predator.getRunningBit()) ? prey.discharge(container,
						predator.getRunningLength()) : prey.dischargeNegated(container, predator.getRunningLength());
				container.addStreamOfEmptyWords(predator.getRunningBit(), predator.getRunningLength() - index);
				predator.discardRunningWords();
			}
			final int nbre_literal = Math.min(rlwi.getNumberOfLiteralWords(), rlwj.getNumberOfLiteralWords());
			if(nbre_literal > 0) {
				for(int k = 0; k < nbre_literal; ++k)
					container.addWord(rlwi.getLiteralWordAt(k) ^ rlwj.getLiteralWordAt(k));
				rlwi.discardLiteralWords(nbre_literal);
				rlwj.discardLiteralWords(nbre_literal);
			}
		}
		final boolean i_remains = rlwi.size() > 0;
		final IteratingBufferedRunningLengthWord remaining = i_remains ? rlwi : rlwj;
		remaining.discharge(container);
		container.setSizeInBitsWithinLastWord(Math.max(sizeInBits(), a.sizeInBits()));
	}

	final Buffer buffer;
	private RunningLengthWord rlw;
	private int sizeInBits = 0;
	public static final boolean ADJUST_CONTAINER_SIZE_WHEN_AGGREGATING = true;
	public static final int WORD_IN_BITS = 64;
	static final long serialVersionUID = 1L;

}
