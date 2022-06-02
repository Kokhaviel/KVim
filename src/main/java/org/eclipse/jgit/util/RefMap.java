/*
 * Copyright (C) 2010, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.util;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.BinaryOperator;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefComparator;

public class RefMap extends AbstractMap<String, Ref> {

	final String prefix;
	RefList<Ref> packed;
	RefList<Ref> loose;
	RefList<Ref> resolved;

	int size;

	boolean sizeIsValid;

	private Set<Entry<String, Ref>> entrySet;

	@SuppressWarnings("unchecked")
	public RefMap(String prefix, RefList<? extends Ref> packed,
				  RefList<? extends Ref> loose, RefList<? extends Ref> resolved) {
		this.prefix = prefix;
		this.packed = (RefList<Ref>) packed;
		this.loose = (RefList<Ref>) loose;
		this.resolved = (RefList<Ref>) resolved;
	}

	@Override
	public boolean containsKey(Object name) {
		return get(name) != null;
	}

	@Override
	public Ref get(Object key) {
		String name = toRefName((String) key);
		Ref ref = resolved.get(name);
		if(ref == null)
			ref = loose.get(name);
		if(ref == null)
			ref = packed.get(name);
		return ref;
	}

	@Override
	public Ref put(String keyName, Ref value) {
		String name = toRefName(keyName);

		if(!name.equals(value.getName()))
			throw new IllegalArgumentException();

		if(!resolved.isEmpty()) {
			for(Ref ref : resolved)
				loose = loose.put(ref);
			resolved = RefList.emptyList();
		}

		int idx = loose.find(name);
		if(0 <= idx) {
			Ref prior = loose.get(name);
			loose = loose.set(idx, value);
			return prior;
		}
		Ref prior = get(keyName);
		loose = loose.add(idx, value);
		sizeIsValid = false;
		return prior;
	}

	@Override
	public Ref remove(Object key) {
		String name = toRefName((String) key);
		Ref res = null;
		int idx;
		if(0 <= (idx = packed.find(name))) {
			res = packed.get(name);
			packed = packed.remove(idx);
			sizeIsValid = false;
		}
		if(0 <= (idx = loose.find(name))) {
			res = loose.get(name);
			loose = loose.remove(idx);
			sizeIsValid = false;
		}
		if(0 <= (idx = resolved.find(name))) {
			res = resolved.get(name);
			resolved = resolved.remove(idx);
			sizeIsValid = false;
		}
		return res;
	}

	@Override
	public boolean isEmpty() {
		return entrySet().isEmpty();
	}

	@Override
	public Set<Entry<String, Ref>> entrySet() {
		if(entrySet == null) {
			entrySet = new AbstractSet<Entry<String, Ref>>() {

				@Override
				public Iterator<Entry<String, Ref>> iterator() {
					return new SetIterator();
				}

				@Override
				public int size() {
					if(!sizeIsValid) {
						size = 0;
						Iterator<?> i = entrySet().iterator();
						for(; i.hasNext(); i.next())
							size++;
						sizeIsValid = true;
					}
					return size;
				}

				@Override
				public boolean isEmpty() {
					if(sizeIsValid)
						return 0 == size;
					return !iterator().hasNext();
				}

				@Override
				public void clear() {
					packed = RefList.emptyList();
					loose = RefList.emptyList();
					resolved = RefList.emptyList();
					size = 0;
					sizeIsValid = true;
				}
			};
		}
		return entrySet;
	}

	@Override
	public String toString() {
		StringBuilder r = new StringBuilder();
		boolean first = true;
		r.append('[');
		for(Ref ref : values()) {
			if(first)
				first = false;
			else
				r.append(", ");
			r.append(ref);
		}
		r.append(']');
		return r.toString();
	}

	public static Collector<Ref, ?, RefMap> toRefMap(
			BinaryOperator<Ref> mergeFunction) {
		return Collectors.collectingAndThen(RefList.toRefList(mergeFunction),
				(refs) -> new RefMap("",
						refs, RefList.emptyList(),
						RefList.emptyList()));
	}

	private String toRefName(String name) {
		if(0 < prefix.length())
			name = prefix + name;
		return name;
	}

	String toMapKey(Ref ref) {
		String name = ref.getName();
		if(0 < prefix.length())
			name = name.substring(prefix.length());
		return name;
	}

	private class SetIterator implements Iterator<Entry<String, Ref>> {
		private int packedIdx;

		private int looseIdx;

		private int resolvedIdx;

		private Entry<String, Ref> next;

		SetIterator() {
			if(0 < prefix.length()) {
				packedIdx = -(packed.find(prefix) + 1);
				looseIdx = -(loose.find(prefix) + 1);
				resolvedIdx = -(resolved.find(prefix) + 1);
			}
		}

		@Override
		public boolean hasNext() {
			if(next == null)
				next = peek();
			return next != null;
		}

		@Override
		public Entry<String, Ref> next() {
			if(hasNext()) {
				Entry<String, Ref> r = next;
				next = peek();
				return r;
			}
			throw new NoSuchElementException();
		}

		public Entry<String, Ref> peek() {
			if(packedIdx < packed.size() && looseIdx < loose.size()) {
				Ref p = packed.get(packedIdx);
				Ref l = loose.get(looseIdx);
				int cmp = RefComparator.compareTo(p, l);
				if(cmp < 0) {
					packedIdx++;
					return toEntry(p);
				}

				if(cmp == 0)
					packedIdx++;
				looseIdx++;
				return toEntry(resolveLoose(l));
			}

			if(looseIdx < loose.size())
				return toEntry(resolveLoose(loose.get(looseIdx++)));
			if(packedIdx < packed.size())
				return toEntry(packed.get(packedIdx++));
			return null;
		}

		private Ref resolveLoose(Ref l) {
			if(resolvedIdx < resolved.size()) {
				Ref r = resolved.get(resolvedIdx);
				int cmp = RefComparator.compareTo(l, r);
				if(cmp == 0) {
					resolvedIdx++;
					return r;
				} else if(cmp > 0) {
					throw new IllegalStateException();
				}
			}
			return l;
		}

		private Ent toEntry(Ref p) {
			if(p.getName().startsWith(prefix))
				return new Ent(p);
			packedIdx = packed.size();
			looseIdx = loose.size();
			resolvedIdx = resolved.size();
			return null;
		}

		@Override
		public void remove() {
			throw new UnsupportedOperationException();
		}
	}

	private class Ent implements Entry<String, Ref> {
		private Ref ref;

		Ent(Ref ref) {
			this.ref = ref;
		}

		@Override
		public String getKey() {
			return toMapKey(ref);
		}

		@Override
		public Ref getValue() {
			return ref;
		}

		@Override
		public Ref setValue(Ref value) {
			Ref prior = put(getKey(), value);
			ref = value;
			return prior;
		}

		@Override
		public int hashCode() {
			return getKey().hashCode();
		}

		@Override
		public boolean equals(Object obj) {
			if(obj instanceof Map.Entry) {
				final Object key = ((Entry<?, ?>) obj).getKey();
				final Object val = ((Entry<?, ?>) obj).getValue();
				if(key instanceof String && val instanceof Ref) {
					final Ref r = (Ref) val;
					if(r.getName().equals(ref.getName())) {
						final ObjectId a = r.getObjectId();
						final ObjectId b = ref.getObjectId();
						return a != null && b != null
								&& AnyObjectId.isEqual(a, b);
					}
				}
			}
			return false;
		}

		@Override
		public String toString() {
			return ref.toString();
		}
	}
}
