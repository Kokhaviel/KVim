/*
 * Copyright (C) 2010, Mathias Kinzler <mathias.kinzler@sap.com>
 * Copyright (C) 2009, Constantine Plotnikov <constantine.plotnikov@gmail.com>
 * Copyright (C) 2007, Dave Watson <dwatson@mimvista.com>
 * Copyright (C) 2008-2010, Google Inc.
 * Copyright (C) 2009, Google, Inc.
 * Copyright (C) 2009, JetBrains s.r.o.
 * Copyright (C) 2007-2008, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2006-2008, Shawn O. Pearce <spearce@spearce.org>
 * Copyright (C) 2008, Thad Hughes <thadh@thad.corp.google.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.lib;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.File;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.events.ConfigChangedEvent;
import org.eclipse.jgit.events.ConfigChangedListener;
import org.eclipse.jgit.events.ListenerHandle;
import org.eclipse.jgit.events.ListenerList;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.RawParseUtils;
import org.eclipse.jgit.util.StringUtils;

public class Config {

	private static final String[] EMPTY_STRING_ARRAY = {};
	private static final int MAX_DEPTH = 10;
	private static final TypedConfigGetter DEFAULT_GETTER = new DefaultTypedConfigGetter();
	private static final TypedConfigGetter typedGetter = DEFAULT_GETTER;
	private final ListenerList listeners = new ListenerList();
	private final AtomicReference<ConfigSnapshot> state;
	private final Config baseConfig;
	private static final String MISSING_ENTRY = "";

	public Config() {
		this(null);
	}

	public Config(Config defaultConfig) {
		baseConfig = defaultConfig;
		state = new AtomicReference<>(newState());
	}

	public Config getBaseConfig() {
		return baseConfig;
	}

	@SuppressWarnings({"ReferenceEquality", "StringEquality"})
	public static boolean isMissing(String value) {
		return value == MISSING_ENTRY;
	}

	static String escapeValue(String x) {
		if(x.isEmpty()) {
			return "";
		}

		boolean needQuote = x.charAt(0) == ' ' || x.charAt(x.length() - 1) == ' ';
		StringBuilder r = new StringBuilder(x.length());
		for(int k = 0; k < x.length(); k++) {
			char c = x.charAt(k);
			switch(c) {
				case '\0':
					throw new IllegalArgumentException(
							JGitText.get().configValueContainsNullByte);

				case '\n':
					r.append('\\').append('n');
					break;

				case '\t':
					r.append('\\').append('t');
					break;

				case '\b':
					r.append('\\').append('b');
					break;

				case '\\':
					r.append('\\').append('\\');
					break;

				case '"':
					r.append('\\').append('"');
					break;

				case '#':
				case ';':
					needQuote = true;
					r.append(c);
					break;

				default:
					r.append(c);
					break;
			}
		}

		return needQuote ? '"' + r.toString() + '"' : r.toString();
	}

	public int getInt(final String section, final String name,
					  final int defaultValue) {
		return typedGetter.getInt(this, section, null, name, defaultValue);
	}

	public int getInt(final String section, String subsection,
					  final String name, final int defaultValue) {
		return typedGetter.getInt(this, section, subsection, name,
				defaultValue);
	}

	public long getLong(String section, String name, long defaultValue) {
		return typedGetter.getLong(this, section, null, name, defaultValue);
	}

	public long getLong(final String section, String subsection,
						final String name, final long defaultValue) {
		return typedGetter.getLong(this, section, subsection, name,
				defaultValue);
	}

	public boolean getBoolean(final String section, final String name,
							  final boolean defaultValue) {
		return typedGetter.getBoolean(this, section, null, name, defaultValue);
	}

	public boolean getBoolean(final String section, String subsection,
							  final String name, final boolean defaultValue) {
		return typedGetter.getBoolean(this, section, subsection, name,
				defaultValue);
	}

	public <T extends Enum<?>> T getEnum(final String section,
										 final String subsection, final String name, final T defaultValue) {
		final T[] all = allValuesOf(defaultValue);
		return typedGetter.getEnum(this, all, section, subsection, name,
				defaultValue);
	}

	@SuppressWarnings("unchecked")
	private static <T> T[] allValuesOf(T value) {
		try {
			return (T[]) value.getClass().getMethod("values").invoke(null);
		} catch(Exception err) {
			String typeName = value.getClass().getName();
			String msg = MessageFormat.format(
					JGitText.get().enumValuesNotAvailable, typeName);
			throw new IllegalArgumentException(msg, err);
		}
	}

	public <T extends Enum<?>> T getEnum(final T[] all, final String section,
										 final String subsection, final String name, final T defaultValue) {
		return typedGetter.getEnum(this, all, section, subsection, name,
				defaultValue);
	}

	public String getString(final String section, String subsection,
							final String name) {
		return getRawString(section, subsection, name);
	}

	public String[] getStringList(final String section, String subsection,
								  final String name) {
		String[] base;
		if(baseConfig != null)
			base = baseConfig.getStringList(section, subsection, name);
		else
			base = EMPTY_STRING_ARRAY;

		String[] self = getRawStringList(section, subsection, name);
		if(self == null)
			return base;
		if(base.length == 0)
			return self;
		String[] res = new String[base.length + self.length];
		int n = base.length;
		System.arraycopy(base, 0, res, 0, n);
		System.arraycopy(self, 0, res, n, self.length);
		return res;
	}

	public long getTimeUnit(String section, String subsection, String name,
							long defaultValue, TimeUnit wantUnit) {
		return typedGetter.getTimeUnit(this, section, subsection, name,
				defaultValue, wantUnit);
	}

	public Path getPath(String section, String subsection, String name,
						@NonNull FS fs, File resolveAgainst, Path defaultValue) {
		return typedGetter.getPath(this, section, subsection, name, fs,
				resolveAgainst, defaultValue);
	}

	public List<RefSpec> getRefSpecs(String section, String subsection,
									 String name) {
		return typedGetter.getRefSpecs(this, section, subsection, name);
	}

	public Set<String> getSubsections(String section) {
		return getState().getSubsections(section);
	}

	public Set<String> getNames(String section) {
		return getNames(section, null);
	}

	public Set<String> getNames(String section, String subsection) {
		return getState().getNames(section, subsection);
	}

	@SuppressWarnings("unchecked")
	public <T> T get(SectionParser<T> parser) {
		final ConfigSnapshot myState = getState();
		T obj = (T) myState.cache.get(parser);
		if(obj == null) {
			obj = parser.parse(this);
			myState.cache.put(parser, obj);
		}
		return obj;
	}

	public ListenerHandle addChangeListener(ConfigChangedListener listener) {
		return listeners.addConfigChangedListener(listener);
	}

	protected boolean notifyUponTransientChanges() {
		return true;
	}

	protected void fireConfigChangedEvent() {
		listeners.dispatch(new ConfigChangedEvent());
	}

	String getRawString(final String section, final String subsection,
						final String name) {
		String[] lst = getRawStringList(section, subsection, name);
		if(lst != null) {
			return lst[lst.length - 1];
		} else if(baseConfig != null) {
			return baseConfig.getRawString(section, subsection, name);
		} else {
			return null;
		}
	}

	private String[] getRawStringList(String section, String subsection,
									  String name) {
		return state.get().get(section, subsection, name);
	}

	private ConfigSnapshot getState() {
		ConfigSnapshot cur, upd;
		do {
			cur = state.get();
			final ConfigSnapshot base = getBaseState();
			if(cur.baseState == base)
				return cur;
			upd = new ConfigSnapshot(cur.entryList, base);
		} while(!state.compareAndSet(cur, upd));
		return upd;
	}

	private ConfigSnapshot getBaseState() {
		return baseConfig != null ? baseConfig.getState() : null;
	}

	public void setInt(final String section, final String subsection,
					   final String name, final int value) {
		setLong(section, subsection, name, value);
	}

	public void setLong(final String section, final String subsection,
						final String name, final long value) {
		setString(section, subsection, name,
				StringUtils.formatWithSuffix(value));
	}

	public void setBoolean(final String section, final String subsection,
						   final String name, final boolean value) {
		setString(section, subsection, name, value ? "true" : "false");
	}

	public <T extends Enum<?>> void setEnum(final String section,
											final String subsection, final String name, final T value) {
		String n;
		if(value instanceof ConfigEnum)
			n = ((ConfigEnum) value).toConfigValue();
		else
			n = value.name().toLowerCase(Locale.ROOT).replace('_', ' ');
		setString(section, subsection, name, n);
	}

	public void setString(final String section, final String subsection,
						  final String name, final String value) {
		setStringList(section, subsection, name, Collections
				.singletonList(value));
	}

	public void unset(final String section, final String subsection,
					  final String name) {
		setStringList(section, subsection, name, Collections.emptyList());
	}

	public void unsetSection(String section, String subsection) {
		ConfigSnapshot src, res;
		do {
			src = state.get();
			res = unsetSection(src, section, subsection);
		} while(!state.compareAndSet(src, res));
	}

	private ConfigSnapshot unsetSection(final ConfigSnapshot srcState,
										final String section,
										final String subsection) {
		final int max = srcState.entryList.size();
		final ArrayList<ConfigLine> r = new ArrayList<>(max);

		boolean lastWasMatch = false;
		for(ConfigLine e : srcState.entryList) {
			if(e.includedFrom == null && e.match(section, subsection)) {
				lastWasMatch = true;
				continue;
			}

			if(lastWasMatch && e.section == null && e.subsection == null)
				continue;
			r.add(e);
		}

		return newState(r);
	}

	public void setStringList(final String section, final String subsection,
							  final String name, final List<String> values) {
		ConfigSnapshot src, res;
		do {
			src = state.get();
			res = replaceStringList(src, section, subsection, name, values);
		} while(!state.compareAndSet(src, res));
		if(notifyUponTransientChanges())
			fireConfigChangedEvent();
	}

	private ConfigSnapshot replaceStringList(final ConfigSnapshot srcState,
											 final String section, final String subsection, final String name,
											 final List<String> values) {
		final List<ConfigLine> entries = copy(srcState, values);
		int entryIndex = 0;
		int valueIndex = 0;
		int insertPosition = -1;

		while(entryIndex < entries.size() && valueIndex < values.size()) {
			final ConfigLine e = entries.get(entryIndex);
			if(e.includedFrom == null && e.match(section, subsection, name)) {
				entries.set(entryIndex, e.forValue(values.get(valueIndex++)));
				insertPosition = entryIndex + 1;
			}
			entryIndex++;
		}

		if(valueIndex == values.size() && entryIndex < entries.size()) {
			while(entryIndex < entries.size()) {
				final ConfigLine e = entries.get(entryIndex++);
				if(e.includedFrom == null
						&& e.match(section, subsection, name))
					entries.remove(--entryIndex);
			}
		}

		if(valueIndex < values.size() && entryIndex == entries.size()) {
			if(insertPosition < 0) {
				insertPosition = findSectionEnd(entries, section, subsection
				);
			}
			if(insertPosition < 0) {
				final ConfigLine e = new ConfigLine();
				e.section = section;
				e.subsection = subsection;
				entries.add(e);
				insertPosition = entries.size();
			}
			while(valueIndex < values.size()) {
				final ConfigLine e = new ConfigLine();
				e.section = section;
				e.subsection = subsection;
				e.name = name;
				e.value = values.get(valueIndex++);
				entries.add(insertPosition++, e);
			}
		}

		return newState(entries);
	}

	private static List<ConfigLine> copy(final ConfigSnapshot src,
										 final List<String> values) {
		final int max = src.entryList.size() + values.size() + 1;
		final ArrayList<ConfigLine> r = new ArrayList<>(max);
		r.addAll(src.entryList);
		return r;
	}

	private static int findSectionEnd(final List<ConfigLine> entries,
									  final String section, final String subsection) {
		for(int i = 0; i < entries.size(); i++) {
			ConfigLine e = entries.get(i);
			if(e.includedFrom != null) {
				continue;
			}

			if(e.match(section, subsection, null)) {
				i++;
				while(i < entries.size()) {
					e = entries.get(i);
					if(e.match(section, subsection, e.name))
						i++;
					else
						break;
				}
				return i;
			}
		}
		return -1;
	}

	public String toText() {
		final StringBuilder out = new StringBuilder();
		for(ConfigLine e : state.get().entryList) {
			if(e.includedFrom != null)
				continue;
			if(e.prefix != null)
				out.append(e.prefix);
			if(e.section != null && e.name == null) {
				out.append('[');
				out.append(e.section);
				if(e.subsection != null) {
					out.append(' ');
					String escaped = escapeValue(e.subsection);
					boolean quoted = escaped.startsWith("\"")
							&& escaped.endsWith("\"");
					if(!quoted)
						out.append('"');
					out.append(escaped);
					if(!quoted)
						out.append('"');
				}
				out.append(']');
			} else if(e.section != null) {
				if(e.prefix == null || "".equals(e.prefix))
					out.append('\t');
				out.append(e.name);
				if(!isMissing(e.value)) {
					out.append(" =");
					if(e.value != null) {
						out.append(' ');
						out.append(escapeValue(e.value));
					}
				}
				if(e.suffix != null)
					out.append(' ');
			}
			if(e.suffix != null)
				out.append(e.suffix);
			out.append('\n');
		}
		return out.toString();
	}

	public void fromText(String text) throws ConfigInvalidException {
		state.set(newState(fromTextRecurse(text, 1, null)));
	}

	private List<ConfigLine> fromTextRecurse(String text, int depth,
											 String includedFrom) throws ConfigInvalidException {
		if(depth > MAX_DEPTH) {
			throw new ConfigInvalidException(
					JGitText.get().tooManyIncludeRecursions);
		}
		final List<ConfigLine> newEntries = new ArrayList<>();
		final StringReader in = new StringReader(text);
		ConfigLine last = null;
		ConfigLine e = new ConfigLine();
		e.includedFrom = includedFrom;
		for(; ; ) {
			int input = in.read();
			if(-1 == input) {
				if(e.section != null)
					newEntries.add(e);
				break;
			}

			final char c = (char) input;
			if('\n' == c) {
				newEntries.add(e);
				if(e.section != null)
					last = e;
				e = new ConfigLine();
				e.includedFrom = includedFrom;
			} else if(e.suffix != null) {
				e.suffix += c;

			} else if(';' == c || '#' == c) {
				e.suffix = String.valueOf(c);

			} else if(e.section == null && Character.isWhitespace(c)) {
				if(e.prefix == null)
					e.prefix = "";
				e.prefix += c;

			} else if('[' == c) {
				e.section = readSectionName(in);
				input = in.read();
				if('"' == input) {
					e.subsection = readSubsectionName(in);
					input = in.read();
				}
				if(']' != input)
					throw new ConfigInvalidException(JGitText.get().badGroupHeader);
				e.suffix = "";

			} else if(last != null) {
				e.section = last.section;
				e.subsection = last.subsection;
				in.reset();
				e.name = readKeyName(in);
				if(e.name.endsWith("\n")) {
					e.name = e.name.substring(0, e.name.length() - 1);
					e.value = MISSING_ENTRY;
				} else
					e.value = readValue(in);

				if(e.section.equalsIgnoreCase("include")) {
					addIncludedConfig(newEntries, e, depth);
				}
			} else
				throw new ConfigInvalidException(JGitText.get().invalidLineInConfigFile);
		}

		return newEntries;
	}

	protected byte[] readIncludedConfig(String relPath)
			throws ConfigInvalidException {
		return null;
	}

	private void addIncludedConfig(final List<ConfigLine> newEntries,
								   ConfigLine line, int depth) throws ConfigInvalidException {
		if(!line.name.equalsIgnoreCase("path") ||
				line.value == null || line.value.equals(MISSING_ENTRY)) {
			throw new ConfigInvalidException(MessageFormat.format(
					JGitText.get().invalidLineInConfigFileWithParam, line));
		}
		byte[] bytes = readIncludedConfig(line.value);
		if(bytes == null) {
			return;
		}

		String decoded;
		if(isUtf8(bytes)) {
			decoded = RawParseUtils.decode(UTF_8, bytes, 3, bytes.length);
		} else {
			decoded = RawParseUtils.decode(bytes);
		}
		try {
			newEntries.addAll(fromTextRecurse(decoded, depth + 1, line.value));
		} catch(ConfigInvalidException e) {
			throw new ConfigInvalidException(MessageFormat
					.format(JGitText.get().cannotReadFile, line.value), e);
		}
	}

	private ConfigSnapshot newState() {
		return new ConfigSnapshot(Collections.emptyList(),
				getBaseState());
	}

	private ConfigSnapshot newState(List<ConfigLine> entries) {
		return new ConfigSnapshot(Collections.unmodifiableList(entries),
				getBaseState());
	}

	protected void clear() {
		state.set(newState());
	}

	protected boolean isUtf8(final byte[] bytes) {
		return bytes.length >= 3 && bytes[0] == (byte) 0xEF
				&& bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF;
	}

	private static String readSectionName(StringReader in)
			throws ConfigInvalidException {
		final StringBuilder name = new StringBuilder();
		for(; ; ) {
			int c = in.read();
			if(c < 0)
				throw new ConfigInvalidException(JGitText.get().unexpectedEndOfConfigFile);

			if(']' == c) {
				in.reset();
				break;
			}

			if(' ' == c || '\t' == c) {
				for(; ; ) {
					c = in.read();
					if(c < 0)
						throw new ConfigInvalidException(JGitText.get().unexpectedEndOfConfigFile);

					if('"' == c) {
						in.reset();
						break;
					}

					if(' ' == c || '\t' == c)
						continue;
					throw new ConfigInvalidException(MessageFormat.format(JGitText.get().badSectionEntry, name));
				}
				break;
			}

			if(Character.isLetterOrDigit((char) c) || '.' == c || '-' == c)
				name.append((char) c);
			else
				throw new ConfigInvalidException(MessageFormat.format(JGitText.get().badSectionEntry, name));
		}
		return name.toString();
	}

	private static String readKeyName(StringReader in)
			throws ConfigInvalidException {
		final StringBuilder name = new StringBuilder();
		for(; ; ) {
			int c = in.read();
			if(c < 0)
				throw new ConfigInvalidException(JGitText.get().unexpectedEndOfConfigFile);

			if('=' == c)
				break;

			if(' ' == c || '\t' == c) {
				for(; ; ) {
					c = in.read();
					if(c < 0)
						throw new ConfigInvalidException(JGitText.get().unexpectedEndOfConfigFile);

					if('=' == c)
						break;

					if(';' == c || '#' == c || '\n' == c) {
						in.reset();
						break;
					}

					if(' ' == c || '\t' == c)
						continue;
					throw new ConfigInvalidException(JGitText.get().badEntryDelimiter);
				}
				break;
			}

			if(Character.isLetterOrDigit((char) c) || c == '-') {
				name.append((char) c);
			} else if('\n' == c) {
				in.reset();
				name.append((char) c);
				break;
			} else
				throw new ConfigInvalidException(MessageFormat.format(JGitText.get().badEntryName, name));
		}
		return name.toString();
	}

	private static String readSubsectionName(StringReader in)
			throws ConfigInvalidException {
		StringBuilder r = new StringBuilder();
		for(; ; ) {
			int c = in.read();
			if(c < 0) {
				break;
			}

			if('\n' == c) {
				throw new ConfigInvalidException(
						JGitText.get().newlineInQuotesNotAllowed);
			}
			if('\\' == c) {
				c = in.read();
				if(c == -1) {
					throw new ConfigInvalidException(JGitText.get().endOfFileInEscape);
				}
				r.append((char) c);
				continue;
			}
			if('"' == c) {
				break;
			}

			r.append((char) c);
		}
		return r.toString();
	}

	private static String readValue(StringReader in)
			throws ConfigInvalidException {
		StringBuilder value = new StringBuilder();
		StringBuilder trailingSpaces = null;
		boolean quote = false;
		boolean inLeadingSpace = true;

		for(; ; ) {
			int c = in.read();
			if(c < 0) {
				break;
			}
			if('\n' == c) {
				if(quote) {
					throw new ConfigInvalidException(
							JGitText.get().newlineInQuotesNotAllowed);
				}
				in.reset();
				break;
			}

			if(!quote && (';' == c || '#' == c)) {
				if(trailingSpaces != null) {
					trailingSpaces.setLength(0);
				}
				in.reset();
				break;
			}

			char cc = (char) c;
			if(Character.isWhitespace(cc)) {
				if(inLeadingSpace) {
					continue;
				}
				if(trailingSpaces == null) {
					trailingSpaces = new StringBuilder();
				}
				trailingSpaces.append(cc);
				continue;
			}
			inLeadingSpace = false;
			if(trailingSpaces != null) {
				value.append(trailingSpaces);
				trailingSpaces.setLength(0);
			}

			if('\\' == c) {
				c = in.read();
				switch(c) {
					case -1:
						throw new ConfigInvalidException(JGitText.get().endOfFileInEscape);
					case '\n':
						continue;
					case 't':
						value.append('\t');
						continue;
					case 'b':
						value.append('\b');
						continue;
					case 'n':
						value.append('\n');
						continue;
					case '\\':
						value.append('\\');
						continue;
					case '"':
						value.append('"');
						continue;
					case '\r': {
						int next = in.read();
						if(next == '\n') {
							continue;
						} else if(next >= 0) {
							in.reset();
						}
						break;
					}
					default:
						break;
				}
				throw new ConfigInvalidException(
						MessageFormat.format(JGitText.get().badEscape,
								Character.isAlphabetic(c)
										? Character.valueOf(((char) c))
										: toUnicodeLiteral(c)));
			}

			if('"' == c) {
				quote = !quote;
				continue;
			}

			value.append(cc);
		}
		return value.length() > 0 ? value.toString() : null;
	}

	private static String toUnicodeLiteral(int c) {
		return String.format("\\u%04x", c);
	}

	public interface SectionParser<T> {

		T parse(Config cfg);
	}

	private static class StringReader {
		private final char[] buf;

		private int pos;

		StringReader(String in) {
			buf = in.toCharArray();
		}

		int read() {
			if(pos >= buf.length) {
				return -1;
			}
			return buf[pos++];
		}

		void reset() {
			pos--;
		}
	}

	public interface ConfigEnum {

		String toConfigValue();

		boolean matchConfigValue(String in);
	}
}
