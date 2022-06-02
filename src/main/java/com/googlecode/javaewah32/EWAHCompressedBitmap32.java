package com.googlecode.javaewah32;

/*
 * Copyright 2009-2016, Daniel Lemire, Cliff Moon, David McIntosh, Robert Becho, Google Inc., Veronika Zenz, Owen Kaser, Gregory Ssi-Yan-Kai, Rory Graves
 * Licensed under the Apache License, Version 2.0.
 */

import com.googlecode.javaewah.IntIterator;
import com.googlecode.javaewah.LogicalElement;

import java.io.*;
import java.util.Iterator;


public final class EWAHCompressedBitmap32 implements Cloneable, Externalizable, Iterable<Integer>, BitmapStorage32, LogicalElement<EWAHCompressedBitmap32> {

	public EWAHCompressedBitmap32() {
		this(new IntArray());

	}

	public EWAHCompressedBitmap32(int bufferSize) {
		this(new IntArray(bufferSize));
	}

	private EWAHCompressedBitmap32(Buffer32 buffer) {
		this.buffer = buffer;
		this.rlw = new RunningLengthWord32(this.buffer, 0);
	}

	@Deprecated
	public void add(final int newData) {
		addWord(newData);
	}

	@Deprecated
	public void add(final int newData, final int bitsThatMatter) {
		addWord(newData, bitsThatMatter);
	}

	@Override
	public void addWord(final int newData) {
		addWord(newData, WORD_IN_BITS);
	}

	public void addWord(final int newData, final int bitsThatMatter) {
		this.sizeInBits += bitsThatMatter;
		if(newData == 0) {
			insertEmptyWord(false);
		} else if(newData == ~0) {
			insertEmptyWord(true);
		} else {
			insertLiteralWord(newData);
		}
	}

	private void insertEmptyWord(final boolean v) {
		final boolean noliteralword = (this.rlw.getNumberOfLiteralWords() == 0);
		final int runlen = this.rlw.getRunningLength();
		if((noliteralword) && (runlen == 0)) {
			this.rlw.setRunningBit(v);
		}
		if((noliteralword) && (this.rlw.getRunningBit() == v)
				&& (runlen < RunningLengthWord32.LARGEST_RUNNING_LENGTH_COUNT)) {
			this.rlw.setRunningLength(runlen + 1);
			return;
		}
		this.buffer.push_back(0);
		this.rlw.position = this.buffer.sizeInWords() - 1;
		this.rlw.setRunningBit(v);
		this.rlw.setRunningLength(1);
	}

	private void insertLiteralWord(final int newData) {
		final int numberSoFar = this.rlw.getNumberOfLiteralWords();
		if(numberSoFar >= RunningLengthWord32.LARGEST_LITERAL_COUNT) {
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
	public void addStreamOfLiteralWords(final Buffer32 buffer, final int start,
										final int number) {
		int leftOverNumber = number;
		while(leftOverNumber > 0) {
			final int numberOfLiteralWords = this.rlw.getNumberOfLiteralWords();
			final int whatWeCanAdd = Math.min(leftOverNumber, RunningLengthWord32.LARGEST_LITERAL_COUNT
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
	public void addStreamOfEmptyWords(final boolean v, int number) {
		if(number == 0)
			return;
		this.sizeInBits += number * WORD_IN_BITS;
		fastaddStreamOfEmptyWords(v, number);
	}

	@Override
	public void addStreamOfNegatedLiteralWords(final Buffer32 buffer,
											   final int start, final int number) {
		int leftOverNumber = number;
		while(leftOverNumber > 0) {
			final int NumberOfLiteralWords = this.rlw.getNumberOfLiteralWords();
			final int whatwecanadd = Math.min(leftOverNumber, RunningLengthWord32.LARGEST_LITERAL_COUNT
					- NumberOfLiteralWords);
			this.rlw.setNumberOfLiteralWords(NumberOfLiteralWords + whatwecanadd);
			leftOverNumber -= whatwecanadd;
			this.buffer.negative_push_back(buffer, start, whatwecanadd);
			this.sizeInBits += whatwecanadd * WORD_IN_BITS;
			if(leftOverNumber > 0) {
				this.buffer.push_back(0);
				this.rlw.position = this.buffer.sizeInWords() - 1;
			}
		}
	}

	@Override
	public EWAHCompressedBitmap32 and(final EWAHCompressedBitmap32 a) {
		int size = Math.max(this.buffer.sizeInWords(), a.buffer.sizeInWords());
		final EWAHCompressedBitmap32 container = new EWAHCompressedBitmap32(size);
		andToContainer(a, container);
		return container;
	}

	public void andToContainer(final EWAHCompressedBitmap32 a,
							   final BitmapStorage32 container) {
		container.clear();
		final EWAHIterator32 i = a.getEWAHIterator();
		final EWAHIterator32 j = getEWAHIterator();
		final IteratingBufferedRunningLengthWord32 rlwi = new IteratingBufferedRunningLengthWord32(i);
		final IteratingBufferedRunningLengthWord32 rlwj = new IteratingBufferedRunningLengthWord32(j);
		while((rlwi.size() > 0) && (rlwj.size() > 0)) {
			while((rlwi.getRunningLength() > 0) || (rlwj.getRunningLength() > 0)) {
				final boolean i_is_prey = rlwi.getRunningLength() < rlwj.getRunningLength();
				final IteratingBufferedRunningLengthWord32 prey = i_is_prey ? rlwi : rlwj;
				final IteratingBufferedRunningLengthWord32 predator = i_is_prey ? rlwj : rlwi;
				if(!predator.getRunningBit()) {
					container.addStreamOfEmptyWords(false,
							predator.getRunningLength());
					prey.discardFirstWords(predator
							.getRunningLength());
				} else {
					final int index = prey.discharge(container, predator.getRunningLength());
					container.addStreamOfEmptyWords(false, predator.getRunningLength() - index);
				}
				predator.discardRunningWords();
			}
			final int nbre_literal = Math.min(
					rlwi.getNumberOfLiteralWords(),
					rlwj.getNumberOfLiteralWords());
			if(nbre_literal > 0) {
				for(int k = 0; k < nbre_literal; ++k)
					container.addWord(rlwi.getLiteralWordAt(k)
							& rlwj.getLiteralWordAt(k));
				rlwi.discardLiteralWords(nbre_literal);
				rlwj.discardLiteralWords(nbre_literal);
			}
		}

		if(ADJUST_CONTAINER_SIZE_WHEN_AGGREGATING) {
			container.setSizeInBitsWithinLastWord(Math.max(sizeInBits(), a.sizeInBits()));
		}
	}

	@Override
	public EWAHCompressedBitmap32 andNot(final EWAHCompressedBitmap32 a) {
		int size = Math.max(this.buffer.sizeInWords(), a.buffer.sizeInWords());
		final EWAHCompressedBitmap32 container = new EWAHCompressedBitmap32(size);
		andNotToContainer(a, container);
		return container;
	}

	public void andNotToContainer(final EWAHCompressedBitmap32 a,
								  final BitmapStorage32 container) {
		container.clear();
		final EWAHIterator32 i = getEWAHIterator();
		final EWAHIterator32 j = a.getEWAHIterator();
		final IteratingBufferedRunningLengthWord32 rlwi = new IteratingBufferedRunningLengthWord32(i);
		final IteratingBufferedRunningLengthWord32 rlwj = new IteratingBufferedRunningLengthWord32(j);
		while((rlwi.size() > 0) && (rlwj.size() > 0)) {
			while((rlwi.getRunningLength() > 0) || (rlwj.getRunningLength() > 0)) {
				final boolean i_is_prey = rlwi.getRunningLength() < rlwj.getRunningLength();
				final IteratingBufferedRunningLengthWord32 prey = i_is_prey ? rlwi : rlwj;
				final IteratingBufferedRunningLengthWord32 predator = i_is_prey ? rlwj : rlwi;
				if(((predator.getRunningBit()) && (i_is_prey)) || ((!predator.getRunningBit()) && (!i_is_prey))) {
					container.addStreamOfEmptyWords(false,
							predator.getRunningLength());
					prey.discardFirstWords(predator.getRunningLength());
				} else if(i_is_prey) {
					final int index = prey.discharge(container,
							predator.getRunningLength());
					container.addStreamOfEmptyWords(false,
							predator.getRunningLength() - index
					);
				} else {
					final int index = prey.dischargeNegated(
							container,
							predator.getRunningLength());
					container.addStreamOfEmptyWords(true,
							predator.getRunningLength() - index);
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
		final IteratingBufferedRunningLengthWord32 remaining = i_remains ? rlwi : rlwj;
		if(i_remains) remaining.discharge(container);
		if(ADJUST_CONTAINER_SIZE_WHEN_AGGREGATING)
			container.setSizeInBitsWithinLastWord(Math.max(sizeInBits(), a.sizeInBits()));

	}

	@Override
	public void clear() {
		this.sizeInBits = 0;
		this.buffer.clear();
		this.rlw.position = 0;
	}

	@Override
	public EWAHCompressedBitmap32 clone() throws CloneNotSupportedException {
		EWAHCompressedBitmap32 clone = new EWAHCompressedBitmap32(this.buffer.clone());
		clone.sizeInBits = this.sizeInBits;
		clone.rlw = new RunningLengthWord32(clone.buffer, this.rlw.position);
		return clone;
	}

	public void serialize(DataOutput out) throws IOException {
		out.writeInt(this.sizeInBits);
		final int siw = this.buffer.sizeInWords();
		out.writeInt(siw);
		for(int i = 0; i < siw; ++i) {
			out.writeInt(this.buffer.getWord(i));
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
			this.buffer.push_back(in.readInt());
		}
		this.rlw = new RunningLengthWord32(this.buffer, in.readInt());
	}

	@Override
	public boolean equals(Object o) {
		if(o instanceof EWAHCompressedBitmap32) {
			try {
				this.xorToContainer((EWAHCompressedBitmap32) o,
						new NonEmptyVirtualStorage32());
				return true;
			} catch(NonEmptyVirtualStorage32.NonEmptyException e) {
				return false;
			}
		}
		return false;
	}

	private void fastaddStreamOfEmptyWords(final boolean v, int number) {
		if((this.rlw.getRunningBit() != v) && (this.rlw.size() == 0)) {
			this.rlw.setRunningBit(v);
		} else if((this.rlw.getNumberOfLiteralWords() != 0)
				|| (this.rlw.getRunningBit() != v)) {
			this.buffer.push_back(0);
			this.rlw.position = this.buffer.sizeInWords() - 1;
			if(v)
				this.rlw.setRunningBit(true);
		}
		final int runLen = this.rlw.getRunningLength();
		final int whatWeCanAdd = Math.min(number, RunningLengthWord32.LARGEST_RUNNING_LENGTH_COUNT - runLen);
		this.rlw.setRunningLength(runLen + whatWeCanAdd);
		number -= whatWeCanAdd;
		while(number >= RunningLengthWord32.LARGEST_RUNNING_LENGTH_COUNT) {
			this.buffer.push_back(0);
			this.rlw.position = this.buffer.sizeInWords() - 1;
			if(v) this.rlw.setRunningBit(true);
			this.rlw.setRunningLength(RunningLengthWord32.LARGEST_RUNNING_LENGTH_COUNT);
			number -= RunningLengthWord32.LARGEST_RUNNING_LENGTH_COUNT;
		}
		if(number > 0) {
			this.buffer.push_back(0);
			this.rlw.position = this.buffer.sizeInWords() - 1;
			if(v)
				this.rlw.setRunningBit(true);
			this.rlw.setRunningLength(number);
		}
	}

	public EWAHIterator32 getEWAHIterator() {
		return new EWAHIterator32(this.buffer);
	}

	@Override
	public int hashCode() {
		int karprabin = 0;
		final int B = 0x9e3779b1;
		final EWAHIterator32 i = this.getEWAHIterator();
		while(i.hasNext()) {
			i.next();
			if(i.rlw.getRunningBit()) {
				final int rl = i.rlw.getRunningLength();
				karprabin += B * rl;
			}
			final int nlw = i.rlw.getNumberOfLiteralWords();
			final int lw = i.literalWords();
			for(int k = 0; k < nlw; ++k) {
				long W = this.buffer.getWord(lw + k);
				karprabin += (int) (B * W);
			}
		}
		return karprabin;
	}

	public IntIterator intIterator() {
		return new IntIteratorImpl32(this.getEWAHIterator());
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
				throw new UnsupportedOperationException("bitsets do not support remove");
			}

			private final IntIterator under = intIterator();
		};
	}

	@Override
	public EWAHCompressedBitmap32 or(final EWAHCompressedBitmap32 a) {
		int size = this.buffer.sizeInWords() + a.buffer.sizeInWords();
		final EWAHCompressedBitmap32 container = new EWAHCompressedBitmap32(size);
		orToContainer(a, container);
		return container;
	}

	public void orToContainer(final EWAHCompressedBitmap32 a, final BitmapStorage32 container) {
		container.clear();
		final EWAHIterator32 i = a.getEWAHIterator();
		final EWAHIterator32 j = getEWAHIterator();
		final IteratingBufferedRunningLengthWord32 rlwi = new IteratingBufferedRunningLengthWord32(i);
		final IteratingBufferedRunningLengthWord32 rlwj = new IteratingBufferedRunningLengthWord32(j);
		while((rlwi.size() > 0) && (rlwj.size() > 0)) {
			while((rlwi.getRunningLength() > 0) || (rlwj.getRunningLength() > 0)) {
				final boolean i_is_prey = rlwi.getRunningLength() < rlwj.getRunningLength();
				final IteratingBufferedRunningLengthWord32 prey = i_is_prey ? rlwi : rlwj;
				final IteratingBufferedRunningLengthWord32 predator = i_is_prey ? rlwj : rlwi;
				if(predator.getRunningBit()) {
					container.addStreamOfEmptyWords(true, predator.getRunningLength());
					prey.discardFirstWords(predator.getRunningLength());
				} else {
					final int index = prey.discharge(container, predator.getRunningLength());
					container.addStreamOfEmptyWords(false,
							predator.getRunningLength() - index
					);
				}
				predator.discardRunningWords();
			}
			final int nbre_literal = Math.min(rlwi.getNumberOfLiteralWords(), rlwj.getNumberOfLiteralWords());
			if(nbre_literal > 0) {
				for(int k = 0; k < nbre_literal; ++k) {
					container.addWord(rlwi.getLiteralWordAt(k) | rlwj.getLiteralWordAt(k));
				}
				rlwi.discardLiteralWords(nbre_literal);
				rlwj.discardLiteralWords(nbre_literal);
			}
		}
		final boolean i_remains = rlwi.size() > 0;
		final IteratingBufferedRunningLengthWord32 remaining = i_remains ? rlwi : rlwj;
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

	public boolean clear(final int i) {
		return set(i, false);
	}

	public boolean set(final int i) {
		return set(i, true);
	}

	private boolean set(final int i, boolean value) {
		if((i > Integer.MAX_VALUE - WORD_IN_BITS) || (i < 0))
			throw new IndexOutOfBoundsException(
					"Position should be between 0 and "
							+ (Integer.MAX_VALUE - WORD_IN_BITS)
			);
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
				if(this.rlw.getNumberOfLiteralWords() > 0 && this.buffer.getLastWord() == 0) {
					this.buffer.removeLastWord();
					this.rlw.setNumberOfLiteralWords(this.rlw.getNumberOfLiteralWords() - 1);
					insertEmptyWord(false);
				}
				if(dist > 1) {
					fastaddStreamOfEmptyWords(false, dist - 1);
				}
				insertLiteralWord(1 << (i % WORD_IN_BITS));
			}
			if(this.rlw.getNumberOfLiteralWords() == 0) {
				this.rlw.setRunningLength(this.rlw.getRunningLength() - 1);
				insertLiteralWord(1 << (i % WORD_IN_BITS));
			}
			this.buffer.orLastWord(1 << (i % WORD_IN_BITS));
			if(this.buffer.getLastWord() == ~0) {
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
			int rl = RunningLengthWord32.getRunningLength(this.buffer, pos);
			boolean rb = RunningLengthWord32.getRunningBit(this.buffer, pos);
			int lw = RunningLengthWord32.getNumberOfLiteralWords(this.buffer, pos);
			int rbits = rl * WORD_IN_BITS;
			if(i < nbits + rbits) {
				setInRunningLength(value, i, nbits, pos, rl, rb, lw);
				return;
			}
			nbits += rbits;
			int lbits = lw * WORD_IN_BITS;
			if(i < nbits + lbits) {
				setInLiteralWords(value, i, nbits, pos, rl, rb, lw);
				return;
			}
			nbits += lbits;
			pos += lw + 1;
		}
	}

	private void setInRunningLength(boolean value, int i, int nbits, int pos, int rl, boolean rb, int lw) {
		if(value != rb) {
			int wordPosition = (i - nbits) / WORD_IN_BITS + 1;
			int addedWords = (wordPosition == rl) ? 1 : 2;
			this.buffer.expand(pos + 1, addedWords);
			int mask = 1 << i % WORD_IN_BITS;
			this.buffer.setWord(pos + 1, value ? mask : ~mask);
			if(this.rlw.position >= pos + 1) {
				this.rlw.position += addedWords;
			}
			if(addedWords == 1) {
				setRLWInfo(pos, rb, rl - 1, lw + 1);
			} else {
				setRLWInfo(pos, rb, wordPosition - 1, 1);
				setRLWInfo(pos + 2, rb, rl - wordPosition, lw);
				if(this.rlw.position == pos) {
					this.rlw.position += 2;
				}
			}
		}
	}

	private void setInLiteralWords(boolean value, int i, int nbits, int pos, int rl, boolean rb, int lw) {
		int wordPosition = (i - nbits) / WORD_IN_BITS + 1;
		int mask = 1 << i % WORD_IN_BITS;
		if(value) {
			this.buffer.orWord(pos + wordPosition, mask);
		} else {
			this.buffer.andWord(pos + wordPosition, ~mask);
		}
		int emptyWord = value ? ~0 : 0;
		if(this.buffer.getWord(pos + wordPosition) == emptyWord) {
			boolean canMergeInCurrentRLW = mergeLiteralWordInCurrentRunningLength(value, rb, rl, wordPosition);
			boolean canMergeInNextRLW = mergeLiteralWordInNextRunningLength(value, lw, pos, wordPosition);
			if(canMergeInCurrentRLW && canMergeInNextRLW) {
				int nextRl = RunningLengthWord32.getRunningLength(this.buffer, pos + 2);
				int nextLw = RunningLengthWord32.getNumberOfLiteralWords(this.buffer, pos + 2);
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
				int nextRLWPos = pos + lw + 1;
				int nextRl = RunningLengthWord32.getRunningLength(this.buffer, nextRLWPos);
				int nextLw = RunningLengthWord32.getNumberOfLiteralWords(this.buffer, nextRLWPos);
				this.buffer.collapse(pos + wordPosition, 1);
				setRLWInfo(pos, rb, rl, lw - 1);
				setRLWInfo(pos + wordPosition, value, nextRl + 1, nextLw);
				if(this.rlw.position >= nextRLWPos) {
					this.rlw.position -= lw + 1 - wordPosition;
				}
			} else {
				setRLWInfo(pos, rb, rl, wordPosition - 1);
				setRLWInfo(pos + wordPosition, value, 1, lw - wordPosition);
				if(this.rlw.position == pos) {
					this.rlw.position += wordPosition;
				}
			}
		}
	}

	private boolean mergeLiteralWordInCurrentRunningLength(boolean value, boolean rb, int rl, int wordPosition) {
		return (value == rb || rl == 0) && wordPosition == 1;
	}

	private boolean mergeLiteralWordInNextRunningLength(boolean value, int lw, int pos, int wordPosition) {
		int nextRLWPos = pos + lw + 1;
		if(lw == wordPosition && nextRLWPos < this.buffer.sizeInWords()) {
			int nextRl = RunningLengthWord32.getRunningLength(this.buffer, nextRLWPos);
			boolean nextRb = RunningLengthWord32.getRunningBit(this.buffer, nextRLWPos);
			return (value == nextRb || nextRl == 0);
		}
		return false;
	}

	private void setRLWInfo(int pos, boolean rb, int rl, int lw) {
		RunningLengthWord32.setRunningBit(this.buffer, pos, rb);
		RunningLengthWord32.setRunningLength(this.buffer, pos, rl);
		RunningLengthWord32.setNumberOfLiteralWords(this.buffer, pos, lw);
	}

	@Override
	public void setSizeInBitsWithinLastWord(final int size) {
		if((size + WORD_IN_BITS - 1) / WORD_IN_BITS > (this.sizeInBits + WORD_IN_BITS - 1) / WORD_IN_BITS) {
			setSizeInBits(size, false);
			return;
		}
		if((size + WORD_IN_BITS - 1) / WORD_IN_BITS != (this.sizeInBits + WORD_IN_BITS - 1) / WORD_IN_BITS)
			throw new RuntimeException("You can only reduce the size of the bitmap within the scope of the last word. " +
					"To extend the bitmap, please call setSizeInbits(int,boolean): " + size + " " + this.sizeInBits
			);
		this.sizeInBits = size;
		final int usedBitsInLast = this.sizeInBits % WORD_IN_BITS;
		if(usedBitsInLast == 0)
			return;
		if(this.rlw.getNumberOfLiteralWords() == 0) {
			if(this.rlw.getRunningLength() > 0) {
				this.rlw.setRunningLength(this.rlw.getRunningLength() - 1);
				final int word = this.rlw.getRunningBit() ? (~0) >>> (WORD_IN_BITS - usedBitsInLast) : 0;
				this.insertLiteralWord(word);
			}
			return;
		}
		this.buffer.andLastWord((~0) >>> (WORD_IN_BITS - usedBitsInLast));
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
					if(this.buffer.getLastWord() == 0) {
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
				final int mask = ((~0) >>> (WORD_IN_BITS - maskWidth)) << maskShift;
				this.buffer.orLastWord(mask);
				if(this.buffer.getLastWord() == ~0) {
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
				final int mask = ((~0) >>> (WORD_IN_BITS - maskWidth)) << maskShift;
				this.buffer.orLastWord(mask);
			}
			this.sizeInBits = size;
		}
		return true;
	}

	private int distanceInWords(final int i) {
		return (i + WORD_IN_BITS) / WORD_IN_BITS - (this.sizeInBits + WORD_IN_BITS - 1) / WORD_IN_BITS;
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

	@Override
	public EWAHCompressedBitmap32 xor(final EWAHCompressedBitmap32 a) {
		int size = this.buffer.sizeInWords() + a.buffer.sizeInWords();
		final EWAHCompressedBitmap32 container = new EWAHCompressedBitmap32(size);
		xorToContainer(a, container);
		return container;
	}

	public void xorToContainer(final EWAHCompressedBitmap32 a,
							   final BitmapStorage32 container) {
		container.clear();
		final EWAHIterator32 i = a.getEWAHIterator();
		final EWAHIterator32 j = getEWAHIterator();
		final IteratingBufferedRunningLengthWord32 rlwi = new IteratingBufferedRunningLengthWord32(i);
		final IteratingBufferedRunningLengthWord32 rlwj = new IteratingBufferedRunningLengthWord32(j);
		while((rlwi.size() > 0) && (rlwj.size() > 0)) {
			while((rlwi.getRunningLength() > 0) || (rlwj.getRunningLength() > 0)) {
				final boolean i_is_prey = rlwi.getRunningLength() < rlwj.getRunningLength();
				final IteratingBufferedRunningLengthWord32 prey = i_is_prey ? rlwi : rlwj;
				final IteratingBufferedRunningLengthWord32 predator = i_is_prey ? rlwj : rlwi;
				final int index = (!predator.getRunningBit()) ? prey.discharge(container,
						predator.getRunningLength()) : prey.dischargeNegated(container, predator.getRunningLength());
				container.addStreamOfEmptyWords(predator.getRunningBit(), predator.getRunningLength() - index
				);
				predator.discardRunningWords();
			}
			final int nbre_literal = Math.min(
					rlwi.getNumberOfLiteralWords(),
					rlwj.getNumberOfLiteralWords());
			if(nbre_literal > 0) {
				for(int k = 0; k < nbre_literal; ++k)
					container.addWord(rlwi.getLiteralWordAt(k) ^ rlwj.getLiteralWordAt(k));
				rlwi.discardLiteralWords(nbre_literal);
				rlwj.discardLiteralWords(nbre_literal);
			}
		}
		final boolean i_remains = rlwi.size() > 0;
		final IteratingBufferedRunningLengthWord32 remaining = i_remains ? rlwi : rlwj;
		remaining.discharge(container);
		container.setSizeInBitsWithinLastWord(Math.max(sizeInBits(), a.sizeInBits()));
	}

	final Buffer32 buffer;
	private RunningLengthWord32 rlw;
	private int sizeInBits = 0;
	public static final boolean ADJUST_CONTAINER_SIZE_WHEN_AGGREGATING = true;
	public static final int WORD_IN_BITS = 32;

	static final long serialVersionUID = 1L;
}
