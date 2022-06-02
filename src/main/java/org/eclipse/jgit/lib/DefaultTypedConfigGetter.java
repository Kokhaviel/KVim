/*
 * Copyright (C) 2017, Thomas Wolf <thomas.wolf@paranor.ch> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.lib;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.Config.ConfigEnum;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.util.StringUtils;

public class DefaultTypedConfigGetter implements TypedConfigGetter {

	@Override
	public boolean getBoolean(Config config, String section, String subsection,
							  String name, boolean defaultValue) {
		String n = config.getRawString(section, subsection, name);
		if(n == null) {
			return defaultValue;
		}
		if(Config.isMissing(n)) {
			return true;
		}
		try {
			return StringUtils.toBoolean(n);
		} catch(IllegalArgumentException err) {
			throw new IllegalArgumentException(MessageFormat.format(
					JGitText.get().invalidBooleanValue, section, name, n), err);
		}
	}

	@Override
	public <T extends Enum<?>> T getEnum(Config config, T[] all, String section,
										 String subsection, String name, T defaultValue) {
		String value = config.getString(section, subsection, name);
		if(value == null) {
			return defaultValue;
		}
		if(all[0] instanceof ConfigEnum) {
			for(T t : all) {
				if(((ConfigEnum) t).matchConfigValue(value)) {
					return t;
				}
			}
		}

		String n = value.replace(' ', '_');

		n = n.replace('-', '_');

		T trueState = null;
		T falseState = null;
		for(T e : all) {
			if(StringUtils.equalsIgnoreCase(e.name(), n)) {
				return e;
			} else if(StringUtils.equalsIgnoreCase(e.name(), "TRUE")) {
				trueState = e;
			} else if(StringUtils.equalsIgnoreCase(e.name(), "FALSE")) {
				falseState = e;
			}
		}

		if(trueState != null && falseState != null) {
			try {
				return StringUtils.toBoolean(n) ? trueState : falseState;
			} catch(IllegalArgumentException ignored) {
			}
		}

		if(subsection != null) {
			throw new IllegalArgumentException(
					MessageFormat.format(JGitText.get().enumValueNotSupported3,
							section, subsection, name, value));
		}
		throw new IllegalArgumentException(MessageFormat.format(
				JGitText.get().enumValueNotSupported2, section, name, value));
	}

	@Override
	public int getInt(Config config, String section, String subsection,
					  String name, int defaultValue) {
		long val = config.getLong(section, subsection, name, defaultValue);
		if(Integer.MIN_VALUE <= val && val <= Integer.MAX_VALUE) {
			return (int) val;
		}
		throw new IllegalArgumentException(MessageFormat
				.format(JGitText.get().integerValueOutOfRange, section, name));
	}

	@Override
	public long getLong(Config config, String section, String subsection,
						String name, long defaultValue) {
		final String str = config.getString(section, subsection, name);
		if(str == null) {
			return defaultValue;
		}
		try {
			return StringUtils.parseLongWithSuffix(str, false);
		} catch(StringIndexOutOfBoundsException e) {
			return defaultValue;
		} catch(NumberFormatException nfe) {
			throw new IllegalArgumentException(MessageFormat.format(
					JGitText.get().invalidIntegerValue, section, name, str),
					nfe);
		}
	}

	@Override
	public long getTimeUnit(Config config, String section, String subsection,
							String name, long defaultValue, TimeUnit wantUnit) {
		String valueString = config.getString(section, subsection, name);

		if(valueString == null) {
			return defaultValue;
		}

		String s = valueString.trim();
		if(s.length() == 0) {
			return defaultValue;
		}

		if(s.startsWith("-")) {
			throw notTimeUnit(section, subsection, name, valueString);
		}

		Matcher m = Pattern.compile("^(0|[1-9]\\d*)\\s*(.*)$")
				.matcher(valueString);
		if(!m.matches()) {
			return defaultValue;
		}

		String digits = m.group(1);
		String unitName = m.group(2).trim();

		TimeUnit inputUnit;
		int inputMul;

		if(unitName.isEmpty()) {
			inputUnit = wantUnit;
			inputMul = 1;

		} else if(match(unitName, "ns", "nanoseconds")) {
			inputUnit = TimeUnit.NANOSECONDS;
			inputMul = 1;

		} else if(match(unitName, "us", "microseconds")) {
			inputUnit = TimeUnit.MICROSECONDS;
			inputMul = 1;

		} else if(match(unitName, "ms", "milliseconds")) {
			inputUnit = TimeUnit.MILLISECONDS;
			inputMul = 1;

		} else if(match(unitName, "s", "sec", "second", "seconds")) {
			inputUnit = TimeUnit.SECONDS;
			inputMul = 1;

		} else if(match(unitName, "m", "min", "minute", "minutes")) {
			inputUnit = TimeUnit.MINUTES;
			inputMul = 1;

		} else if(match(unitName, "h", "hr", "hour", "hours")) {
			inputUnit = TimeUnit.HOURS;
			inputMul = 1;

		} else if(match(unitName, "d", "day", "days")) {
			inputUnit = TimeUnit.DAYS;
			inputMul = 1;

		} else if(match(unitName, "w", "week", "weeks")) {
			inputUnit = TimeUnit.DAYS;
			inputMul = 7;

		} else if(match(unitName, "mon", "month", "months")) {
			inputUnit = TimeUnit.DAYS;
			inputMul = 30;

		} else if(match(unitName, "y", "year", "years")) {
			inputUnit = TimeUnit.DAYS;
			inputMul = 365;

		} else {
			throw notTimeUnit(section, subsection, name, valueString);
		}

		try {
			return wantUnit.convert(Long.parseLong(digits) * inputMul,
					inputUnit);
		} catch(NumberFormatException nfe) {
			IllegalArgumentException iae = notTimeUnit(section, subsection,
					unitName, valueString);
			iae.initCause(nfe);
			throw iae;
		}
	}

	private static boolean match(String a, String... cases) {
		for(String b : cases) {
			if(b != null && b.equalsIgnoreCase(a)) {
				return true;
			}
		}
		return false;
	}

	private static IllegalArgumentException notTimeUnit(String section,
														String subsection, String name, String valueString) {
		if(subsection != null) {
			return new IllegalArgumentException(
					MessageFormat.format(JGitText.get().invalidTimeUnitValue3,
							section, subsection, name, valueString));
		}
		return new IllegalArgumentException(
				MessageFormat.format(JGitText.get().invalidTimeUnitValue2,
						section, name, valueString));
	}

	@Override
	@NonNull
	public List<RefSpec> getRefSpecs(Config config, String section,
									 String subsection, String name) {
		String[] values = config.getStringList(section, subsection, name);
		List<RefSpec> result = new ArrayList<>(values.length);
		for(String spec : values) {
			result.add(new RefSpec(spec));
		}
		return result;
	}
}
