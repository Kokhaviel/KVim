/*
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.revwalk;

import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

public class RevFlagSet extends AbstractSet<RevFlag> {
	int mask;
	private final List<RevFlag> active;

	public RevFlagSet() {
		active = new ArrayList<>();
	}

	@Override
	public boolean contains(Object o) {
		if(o instanceof RevFlag)
			return (mask & ((RevFlag) o).mask) != 0;
		return false;
	}

	@Override
	public boolean containsAll(Collection<?> c) {
		if(c instanceof RevFlagSet) {
			final int cMask = ((RevFlagSet) c).mask;
			return (mask & cMask) == cMask;
		}
		return super.containsAll(c);
	}

	@Override
	public boolean add(RevFlag flag) {
		if((mask & flag.mask) != 0)
			return false;
		mask |= flag.mask;
		int p = 0;
		while(p < active.size() && active.get(p).mask < flag.mask)
			p++;
		active.add(p, flag);
		return true;
	}

	@Override
	public boolean remove(Object o) {
		final RevFlag flag = (RevFlag) o;
		if((mask & flag.mask) == 0)
			return false;
		mask &= ~flag.mask;
		for(int i = 0; i < active.size(); i++)
			if(active.get(i).mask == flag.mask)
				active.remove(i);
		return true;
	}

	@Override
	public Iterator<RevFlag> iterator() {
		final Iterator<RevFlag> i = active.iterator();
		return new Iterator<RevFlag>() {
			private RevFlag current;

			@Override
			public boolean hasNext() {
				return i.hasNext();
			}

			@Override
			public RevFlag next() {
				return current = i.next();
			}

			@Override
			public void remove() {
				mask &= ~current.mask;
				i.remove();
			}
		};
	}

	@Override
	public int size() {
		return active.size();
	}
}
