/*
 * Copyright (C) 2015, Ivan Motsch <ivan.motsch@bsiag.com>,
 * Copyright (C) 2017, Obeo (mathieu.cartaud@obeo.fr)
 *
 * This program and the accompanying materials are made available
 * under the terms of the Eclipse Distribution License v1.0 which
 * accompanies this distribution, is reproduced below, and is
 * available at http://www.eclipse.org/org/documents/edl-v10.php
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or
 * without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above
 *   copyright notice, this list of conditions and the following
 *   disclaimer in the documentation and/or other materials provided
 *   with the distribution.
 *
 * - Neither the name of the Eclipse Foundation, Inc. nor the
 *   names of its contributors may be used to endorse or promote
 *   products derived from this software without specific prior
 *   written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
 * CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.eclipse.jgit.attributes;

import java.util.*;

import org.eclipse.jgit.attributes.Attribute.State;
import org.eclipse.jgit.lib.Constants;

public final class Attributes {
	private final Map<String, Attribute> map = new LinkedHashMap<>();

	public Attributes(Attribute... attributes) {
		if(attributes != null) {
			for(Attribute a : attributes) {
				put(a);
			}
		}
	}

	public Attribute get(String key) {
		return map.get(key);
	}

	public Collection<Attribute> getAll() {
		return new ArrayList<>(map.values());
	}

	public void put(Attribute a) {
		map.put(a.getKey(), a);
	}

	public void remove(String key) {
		map.remove(key);
	}

	public boolean containsKey(String key) {
		return map.containsKey(key);
	}

	public Attribute.State getState(String key) {
		Attribute a = map.get(key);
		return a != null ? a.getState() : Attribute.State.UNSPECIFIED;
	}

	public boolean isSet(String key) {
		return (getState(key) == State.SET);
	}

	public boolean isUnset(String key) {
		return (getState(key) == State.UNSET);
	}

	public boolean isCustom(String key) {
		return (getState(key) == State.CUSTOM);
	}

	public String getValue(String key) {
		Attribute a = map.get(key);
		return a != null ? a.getValue() : null;
	}

	public boolean canBeContentMerged() {
		if(isUnset(Constants.ATTR_MERGE)) {
			return false;
		} else return !isCustom(Constants.ATTR_MERGE)
				|| !Objects.equals(getValue(Constants.ATTR_MERGE), Constants.ATTR_BUILTIN_BINARY_MERGER);
	}

	@Override
	public String toString() {
		StringBuilder buf = new StringBuilder();
		buf.append(getClass().getSimpleName()).append("[").append(" ");
		for(Attribute attr : map.values()) {
			buf.append(attr.toString()).append(" ");
		}
		buf.append("]");
		return buf.toString();
	}

	@Override
	public int hashCode() {
		return map.hashCode();
	}

	@Override
	public boolean equals(Object obj) {
		if(this == obj) return true;
		if(!(obj instanceof Attributes)) return false;
		Attributes other = (Attributes) obj;
		return this.map.equals(other.map);
	}

}
