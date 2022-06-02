/*
 * Copyright (C) 2008, 2020 Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.internal.storage.file.LazyObjectIdSetFile;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.util.SystemReader;

import java.io.File;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import static org.eclipse.jgit.util.StringUtils.equalsIgnoreCase;
import static org.eclipse.jgit.util.StringUtils.toLowerCase;

public class TransferConfig {
	private static final String FSCK = "fsck";

	public static final Config.SectionParser<TransferConfig> KEY = TransferConfig::new;

	public enum FsckMode {
		ERROR,
		WARN,
		IGNORE
	}

	public enum ProtocolVersion {
		V0("0"),
		V2("2");

		final String name;

		ProtocolVersion(String name) {
			this.name = name;
		}

		public String version() {
			return name;
		}

		@Nullable
		static ProtocolVersion parse(@Nullable String name) {
			if(name == null) {
				return null;
			}
			for(ProtocolVersion v : ProtocolVersion.values()) {
				if(v.name.equals(name)) {
					return v;
				}
			}
			if("1".equals(name)) {
				return V0;
			}
			return null;
		}
	}

	private final boolean fetchFsck;
	private final boolean receiveFsck;
	private final String fsckSkipList;
	private final EnumSet<ObjectChecker.ErrorType> ignore;
	private final boolean allowInvalidPersonIdent;
	private final boolean safeForWindows;
	private final boolean safeForMacOS;
	private final boolean allowRefInWant;
	private final boolean allowTipSha1InWant;
	private final boolean allowReachableSha1InWant;
	private final boolean allowFilter;
	private final boolean allowSidebandAll;

	private final boolean advertiseSidebandAll;
	private final boolean advertiseWaitForDone;

	final @Nullable ProtocolVersion protocolVersion;
	final String[] hideRefs;

	public TransferConfig(Repository db) {
		this(db.getConfig());
	}

	@SuppressWarnings("nls")
	public TransferConfig(Config rc) {
		boolean fsck = rc.getBoolean("transfer", "fsckobjects", false);
		fetchFsck = rc.getBoolean("fetch", "fsckobjects", fsck);
		receiveFsck = rc.getBoolean("receive", "fsckobjects", fsck);
		fsckSkipList = rc.getString(FSCK, null, "skipList");
		allowInvalidPersonIdent = rc.getBoolean(FSCK, "allowInvalidPersonIdent",
				false);
		safeForWindows = rc.getBoolean(FSCK, "safeForWindows",
				SystemReader.getInstance().isWindows());
		safeForMacOS = rc.getBoolean(FSCK, "safeForMacOS",
				SystemReader.getInstance().isMacOS());

		ignore = EnumSet.noneOf(ObjectChecker.ErrorType.class);
		EnumSet<ObjectChecker.ErrorType> set = EnumSet
				.noneOf(ObjectChecker.ErrorType.class);
		for(String key : rc.getNames(FSCK)) {
			if(equalsIgnoreCase(key, "skipList")
					|| equalsIgnoreCase(key, "allowLeadingZeroFileMode")
					|| equalsIgnoreCase(key, "allowInvalidPersonIdent")
					|| equalsIgnoreCase(key, "safeForWindows")
					|| equalsIgnoreCase(key, "safeForMacOS")) {
				continue;
			}

			ObjectChecker.ErrorType id = FsckKeyNameHolder.parse(key);
			if(id != null) {
				switch(rc.getEnum(FSCK, null, key, FsckMode.ERROR)) {
					case ERROR:
						ignore.remove(id);
						break;
					case WARN:
					case IGNORE:
						ignore.add(id);
						break;
				}
				set.add(id);
			}
		}
		if(!set.contains(ObjectChecker.ErrorType.ZERO_PADDED_FILEMODE)
				&& rc.getBoolean(FSCK, "allowLeadingZeroFileMode", false)) {
			ignore.add(ObjectChecker.ErrorType.ZERO_PADDED_FILEMODE);
		}

		allowRefInWant = rc.getBoolean("uploadpack", "allowrefinwant", false);
		allowTipSha1InWant = rc.getBoolean(
				"uploadpack", "allowtipsha1inwant", false);
		allowReachableSha1InWant = rc.getBoolean(
				"uploadpack", "allowreachablesha1inwant", false);
		allowFilter = rc.getBoolean(
				"uploadpack", "allowfilter", false);
		protocolVersion = ProtocolVersion.parse(rc
				.getString(ConfigConstants.CONFIG_PROTOCOL_SECTION, null,
						ConfigConstants.CONFIG_KEY_VERSION));
		hideRefs = rc.getStringList("uploadpack", null, "hiderefs");
		allowSidebandAll = rc.getBoolean(
				"uploadpack", "allowsidebandall", false);
		advertiseSidebandAll = rc.getBoolean("uploadpack",
				"advertisesidebandall", false);
		advertiseWaitForDone = rc.getBoolean("uploadpack",
				"advertisewaitfordone", false);
	}

	@Nullable
	public ObjectChecker newObjectChecker() {
		return newObjectChecker(fetchFsck);
	}

	@Nullable
	public ObjectChecker newReceiveObjectChecker() {
		return newObjectChecker(receiveFsck);
	}

	private ObjectChecker newObjectChecker(boolean check) {
		if(!check) {
			return null;
		}
		return new ObjectChecker()
				.setIgnore(ignore)
				.setAllowInvalidPersonIdent(allowInvalidPersonIdent)
				.setSafeForWindows(safeForWindows)
				.setSafeForMacOS(safeForMacOS)
				.setSkipList(skipList());
	}

	private ObjectIdSet skipList() {
		if(fsckSkipList != null && !fsckSkipList.isEmpty()) {
			return new LazyObjectIdSetFile(new File(fsckSkipList));
		}
		return null;
	}

	public boolean isAllowTipSha1InWant() {
		return allowTipSha1InWant;
	}

	public boolean isAllowReachableSha1InWant() {
		return allowReachableSha1InWant;
	}

	public boolean isAllowFilter() {
		return allowFilter;
	}

	public boolean isAllowRefInWant() {
		return allowRefInWant;
	}

	public boolean isAllowSidebandAll() {
		return allowSidebandAll;
	}

	public boolean isAdvertiseSidebandAll() {
		return advertiseSidebandAll && allowSidebandAll;
	}

	public boolean isAdvertiseWaitForDone() {
		return advertiseWaitForDone;
	}

	public RefFilter getRefFilter() {
		if(hideRefs.length == 0)
			return RefFilter.DEFAULT;

		return new RefFilter() {
			@Override
			public Map<String, Ref> filter(Map<String, Ref> refs) {
				Map<String, Ref> result = new HashMap<>();
				for(Map.Entry<String, Ref> e : refs.entrySet()) {
					boolean add = true;
					for(String hide : hideRefs) {
						if(e.getKey().equals(hide) || prefixMatch(hide, e.getKey())) {
							add = false;
							break;
						}
					}
					if(add)
						result.put(e.getKey(), e.getValue());
				}
				return result;
			}

			private boolean prefixMatch(String p, String s) {
				return p.charAt(p.length() - 1) == '/' && s.startsWith(p);
			}
		};
	}

	boolean hasDefaultRefFilter() {
		return hideRefs.length == 0;
	}

	static class FsckKeyNameHolder {
		private static final Map<String, ObjectChecker.ErrorType> errors;

		static {
			errors = new HashMap<>();
			for(ObjectChecker.ErrorType m : ObjectChecker.ErrorType.values()) {
				errors.put(keyNameFor(m.name()), m);
			}
		}

		@Nullable
		static ObjectChecker.ErrorType parse(String key) {
			return errors.get(toLowerCase(key));
		}

		private static String keyNameFor(String name) {
			StringBuilder r = new StringBuilder(name.length());
			for(int i = 0; i < name.length(); i++) {
				char c = name.charAt(i);
				if(c != '_') {
					r.append(c);
				}
			}
			return toLowerCase(r.toString());
		}

		private FsckKeyNameHolder() {
		}
	}
}
