/*
 * Copyright (c) 2021 Qualcomm Innovation Center, Inc.
 * and other copyright owners as documented in the project's IP log.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.file;

import java.io.File;
import java.text.MessageFormat;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.eclipse.jgit.lib.ObjectId;

public class PackFile extends File {
	private static final long serialVersionUID = 1L;
	private static final String PREFIX = "pack-";
	private final String base;
	private final String id;
	private final boolean hasOldPrefix;
	private final PackExt packExt;

	private static String createName(String id, PackExt extension) {
		return PREFIX + id + '.' + extension.getExtension();
	}

	public PackFile(File file) {
		this(file.getParentFile(), file.getName());
	}

	public PackFile(File directory, ObjectId id, PackExt ext) {
		this(directory, id.name(), ext);
	}

	public PackFile(File directory, String id, PackExt ext) {
		this(directory, createName(id, ext));
	}

	public PackFile(File directory, String name) {
		super(directory, name);
		int dot = name.lastIndexOf('.');

		if(dot < 0) {
			base = name;
			hasOldPrefix = false;
			packExt = null;
		} else {
			base = name.substring(0, dot);
			String tail = name.substring(dot + 1);
			packExt = getPackExt(tail);
			String old = tail.substring(0,
					tail.length() - getExtension().length());
			hasOldPrefix = old.equals(getExtPrefix(true));
		}

		id = base.startsWith(PREFIX) ? base.substring(PREFIX.length()) : base;
	}

	public String getId() {
		return id;
	}

	public PackExt getPackExt() {
		return packExt;
	}

	public PackFile create(PackExt ext) {
		return new PackFile(getParentFile(), getName(ext));
	}

	public PackFile createForDirectory(File directory) {
		return new PackFile(directory, getName(false));
	}

	public PackFile createPreservedForDirectory(File directory) {
		return new PackFile(directory, getName(true));
	}

	private String getName(PackExt ext) {
		return base + '.' + getExtPrefix(hasOldPrefix) + ext.getExtension();
	}

	private String getName(boolean isPreserved) {
		return base + '.' + getExtPrefix(isPreserved) + getExtension();
	}

	private String getExtension() {
		return packExt == null ? "" : packExt.getExtension();
	}

	private static String getExtPrefix(boolean isPreserved) {
		return isPreserved ? "old-" : "";
	}

	private static PackExt getPackExt(String endsWithExtension) {
		for(PackExt ext : PackExt.values()) if(endsWithExtension.endsWith(ext.getExtension())) return ext;
		throw new IllegalArgumentException(MessageFormat.format(JGitText.get().unrecognizedPackExtension, endsWithExtension));
	}
}
