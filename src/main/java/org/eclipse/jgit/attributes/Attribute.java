/*
 * Copyright (C) 2010, Marc Strapetz <marc.strapetz@syntevo.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.attributes;

public final class Attribute {

	public enum State {
		SET,
		UNSET,
		UNSPECIFIED,
		CUSTOM
	}

	private final String key;
	private final State state;
	private final String value;

	public Attribute(String key, State state) {
		this(key, state, null);
	}

	private Attribute(String key, State state, String value) {
		if(key == null) throw new NullPointerException("The key of an attribute should not be null");
		if(state == null) throw new NullPointerException("The state of an attribute should not be null");

		this.key = key;
		this.state = state;
		this.value = value;
	}

	public Attribute(String key, String value) {
		this(key, State.CUSTOM, value);
	}

	@Override
	public boolean equals(Object obj) {
		if(this == obj) return true;
		if(!(obj instanceof Attribute)) return false;
		Attribute other = (Attribute) obj;
		if(!key.equals(other.key)) return false;
		if(state != other.state) return false;
		if(value == null) {
			return other.value == null;
		} else return value.equals(other.value);
	}

	public String getKey() {
		return key;
	}

	public State getState() {
		return state;
	}

	public String getValue() {
		return value;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + key.hashCode();
		result = prime * result + state.hashCode();
		result = prime * result + ((value == null) ? 0 : value.hashCode());
		return result;
	}

	@Override
	public String toString() {
		switch(state) {
			case SET:
				return key;
			case UNSET:
				return "-" + key;
			case UNSPECIFIED:
				return "!" + key;
			case CUSTOM:
			default:
				return key + "=" + value;
		}
	}
}
