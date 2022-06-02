/*
 * Copyright (C) 2008-2009, Johannes E. Schindelin <johannes.schindelin@gmx.de>
 * Copyright (C) 2009, Johannes Schindelin <johannes.schindelin@gmx.de> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.diff;

import java.text.MessageFormat;

import org.eclipse.jgit.errors.DiffInterruptedException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.util.IntList;
import org.eclipse.jgit.util.LongList;

@SuppressWarnings("hiding")
public class MyersDiff<S extends Sequence> {

	public static final DiffAlgorithm INSTANCE = new LowLevelDiffAlgorithm() {
		@Override
		public <T extends Sequence> void diffNonCommon(EditList edits,
													   HashedSequenceComparator<T> cmp, HashedSequence<T> a,
													   HashedSequence<T> b, Edit region) {
			new MyersDiff<>(edits, cmp, a, b, region);
		}
	};

	protected EditList edits;
	protected HashedSequenceComparator<S> cmp;
	protected HashedSequence<S> a;
	protected HashedSequence<S> b;

	private MyersDiff(EditList edits, HashedSequenceComparator<S> cmp, HashedSequence<S> a, HashedSequence<S> b, Edit region) {
		this.edits = edits;
		this.cmp = cmp;
		this.a = a;
		this.b = b;
		calculateEdits(region);
	}

	MiddleEdit middle = new MiddleEdit();

	private void calculateEdits(Edit r) {
		middle.initialize(r.beginA, r.endA, r.beginB, r.endB);
		if(middle.beginA >= middle.endA &&
				middle.beginB >= middle.endB)
			return;

		calculateEdits(middle.beginA, middle.endA,
				middle.beginB, middle.endB);
	}

	protected void calculateEdits(int beginA, int endA,
								  int beginB, int endB) {
		Edit edit = middle.calculate(beginA, endA, beginB, endB);

		if(beginA < edit.beginA || beginB < edit.beginB) {
			int k = edit.beginB - edit.beginA;
			int x = middle.backward.snake(k, edit.beginA);
			calculateEdits(beginA, x, beginB, k + x);
		}

		if(edit.getType() != Edit.Type.EMPTY) edits.add(edits.size(), edit);
		if(endA > edit.endA || endB > edit.endB) {
			int k = edit.endB - edit.endA;
			int x = middle.forward.snake(k, edit.endA);
			calculateEdits(x, endA, k + x, endB);
		}
	}

	class MiddleEdit {
		void initialize(int beginA, int endA, int beginB, int endB) {
			this.beginA = beginA;
			this.endA = endA;
			this.beginB = beginB;
			this.endB = endB;

			int k = beginB - beginA;
			this.beginA = forward.snake(k, beginA);
			this.beginB = k + this.beginA;

			k = endB - endA;
			this.endA = backward.snake(k, endA);
			this.endB = k + this.endA;
		}

		Edit calculate(int beginA, int endA, int beginB, int endB) {
			if(beginA == endA || beginB == endB)
				return new Edit(beginA, endA, beginB, endB);
			this.beginA = beginA;
			this.endA = endA;
			this.beginB = beginB;
			this.endB = endB;

			int minK = beginB - endA;
			int maxK = endB - beginA;

			forward.initialize(beginB - beginA, beginA, minK, maxK);
			backward.initialize(endB - endA, endA, minK, maxK);

			for(int d = 1; ; d++)
				if(forward.calculate(d) ||
						backward.calculate(d))
					return edit;
		}

		EditPaths forward = new ForwardEditPaths();
		EditPaths backward = new BackwardEditPaths();

		protected int beginA, endA, beginB, endB;
		protected Edit edit;

		abstract class EditPaths {
			private final IntList x = new IntList();
			private final LongList snake = new LongList();
			int beginK, endK, middleK;
			int prevBeginK, prevEndK;
			int minK, maxK;

			final int getIndex(int d, int k) {
				if(((d + k - middleK) % 2) != 0)
					throw new RuntimeException(MessageFormat.format(JGitText.get().unexpectedOddResult, d, k, middleK));
				return (d + k - middleK) / 2;
			}

			final int getX(int d, int k) {
				if(k < beginK || k > endK)
					throw new RuntimeException(MessageFormat.format(JGitText.get().kNotInRange, k, beginK, endK));
				return x.get(getIndex(d, k));
			}

			final long getSnake(int d, int k) {
				if(k < beginK || k > endK)
					throw new RuntimeException(MessageFormat.format(JGitText.get().kNotInRange, k, beginK, endK));
				return snake.get(getIndex(d, k));
			}

			private int forceKIntoRange(int k) {
				if(k < minK)
					return minK + ((k ^ minK) & 1);
				else if(k > maxK)
					return maxK - ((k ^ maxK) & 1);
				return k;
			}

			void initialize(int k, int x, int minK, int maxK) {
				this.minK = minK;
				this.maxK = maxK;
				beginK = endK = middleK = k;
				this.x.clear();
				this.x.add(x);
				snake.clear();
				snake.add(newSnake(k, x));
			}

			abstract int snake(int k, int x);

			abstract int getLeft(int x);

			abstract int getRight(int x);

			abstract boolean isBetter(int left, int right);

			abstract void adjustMinMaxK(int k, int x);

			abstract boolean meets(int d, int k, int x, long snake);

			final long newSnake(int k, int x) {
				long y = k + x;
				long ret = ((long) x) << 32;
				return ret | y;
			}

			final int snake2x(long snake) {
				return (int) (snake >>> 32);
			}

			final int snake2y(long snake) {
				return (int) snake;
			}

			final boolean makeEdit(long snake1, long snake2) {
				int x1 = snake2x(snake1), x2 = snake2x(snake2);
				int y1 = snake2y(snake1), y2 = snake2y(snake2);
				if(x1 > x2 || y1 > y2) {
					x1 = x2;
					y1 = y2;
				}
				edit = new Edit(x1, x2, y1, y2);
				return true;
			}

			boolean calculate(int d) {
				prevBeginK = beginK;
				prevEndK = endK;
				beginK = forceKIntoRange(middleK - d);
				endK = forceKIntoRange(middleK + d);
				for(int k = endK; k >= beginK; k -= 2) {
					if(Thread.interrupted()) {
						throw new DiffInterruptedException();
					}
					int left = -1, right = -1;
					long leftSnake = -1L, rightSnake = -1L;
					if(k > prevBeginK) {
						int i = getIndex(d - 1, k - 1);
						left = x.get(i);
						int end = snake(k - 1, left);
						leftSnake = left != end ?
								newSnake(k - 1, end) :
								snake.get(i);
						if(meets(d, k - 1, end, leftSnake))
							return true;
						left = getLeft(end);
					}
					if(k < prevEndK) {
						int i = getIndex(d - 1, k + 1);
						right = x.get(i);
						int end = snake(k + 1, right);
						rightSnake = right != end ?
								newSnake(k + 1, end) :
								snake.get(i);
						if(meets(d, k + 1, end, rightSnake))
							return true;
						right = getRight(end);
					}
					int newX;
					long newSnake;
					if(k >= prevEndK ||
							(k > prevBeginK &&
									isBetter(left, right))) {
						newX = left;
						newSnake = leftSnake;
					} else {
						newX = right;
						newSnake = rightSnake;
					}
					if(meets(d, k, newX, newSnake))
						return true;
					adjustMinMaxK(k, newX);
					int i = getIndex(d, k);
					x.set(i, newX);
					snake.set(i, newSnake);
				}
				return false;
			}
		}

		class ForwardEditPaths extends EditPaths {
			@Override
			final int snake(int k, int x) {
				for(; x < endA && k + x < endB; x++)
					if(!cmp.equals(a, x, b, k + x))
						break;
				return x;
			}

			@Override
			final int getLeft(int x) {
				return x;
			}

			@Override
			final int getRight(int x) {
				return x + 1;
			}

			@Override
			final boolean isBetter(int left, int right) {
				return left > right;
			}

			@Override
			final void adjustMinMaxK(int k, int x) {
				if(x >= endA || k + x >= endB) {
					if(k > backward.middleK)
						maxK = k;
					else
						minK = k;
				}
			}

			@Override
			final boolean meets(int d, int k, int x, long snake) {
				if(k < backward.beginK || k > backward.endK)
					return false;
				if(((d - 1 + k - backward.middleK) % 2) != 0)
					return false;
				if(x < backward.getX(d - 1, k))
					return false;
				makeEdit(snake, backward.getSnake(d - 1, k));
				return true;
			}
		}

		class BackwardEditPaths extends EditPaths {
			@Override
			final int snake(int k, int x) {
				for(; x > beginA && k + x > beginB; x--)
					if(!cmp.equals(a, x - 1, b, k + x - 1))
						break;
				return x;
			}

			@Override
			final int getLeft(int x) {
				return x - 1;
			}

			@Override
			final int getRight(int x) {
				return x;
			}

			@Override
			final boolean isBetter(int left, int right) {
				return left < right;
			}

			@Override
			final void adjustMinMaxK(int k, int x) {
				if(x <= beginA || k + x <= beginB) {
					if(k > forward.middleK)
						maxK = k;
					else
						minK = k;
				}
			}

			@Override
			final boolean meets(int d, int k, int x, long snake) {
				if(k < forward.beginK || k > forward.endK)
					return false;
				if(((d + k - forward.middleK) % 2) != 0)
					return false;
				if(x > forward.getX(d, k))
					return false;
				makeEdit(forward.getSnake(d, k), snake);
				return true;
			}
		}
	}

	public static void main(String[] args) {
		if(args.length != 2) {
			System.err.println(JGitText.get().need2Arguments);
			System.exit(1);
		}
		try {
			RawText a = new RawText(new java.io.File(args[0]));
			RawText b = new RawText(new java.io.File(args[1]));
			EditList r = INSTANCE.diff(RawTextComparator.DEFAULT, a, b);
			System.out.println(r.toString());
		} catch(Exception e) {
			e.printStackTrace();
		}
	}
}
