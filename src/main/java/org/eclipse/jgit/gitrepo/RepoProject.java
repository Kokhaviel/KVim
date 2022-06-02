/*
 * Copyright (C) 2015, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.gitrepo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jgit.lib.Repository;

public class RepoProject implements Comparable<RepoProject> {
	private final String name;
	private final String path;
	private final String revision;
	private final String remote;
	private final Set<String> groups;
	private final List<CopyFile> copyfiles;
	private final List<LinkFile> linkfiles;
	private String recommendShallow;
	private String url;
	private String defaultRevision;

	public static class ReferenceFile {
		final Repository repo;
		final String path;
		final String src;
		final String dest;

		public ReferenceFile(Repository repo, String path, String src, String dest) {
			this.repo = repo;
			this.path = path;
			this.src = src;
			this.dest = dest;
		}
	}

	public static class CopyFile extends ReferenceFile {

		public CopyFile(Repository repo, String path, String src, String dest) {
			super(repo, path, src, dest);
		}

		public void copy() throws IOException {
			File srcFile = new File(repo.getWorkTree(),
					path + "/" + src);
			File destFile = new File(repo.getWorkTree(), dest);
			try(FileInputStream input = new FileInputStream(srcFile);
				FileOutputStream output = new FileOutputStream(destFile)) {
				FileChannel channel = input.getChannel();
				output.getChannel().transferFrom(channel, 0, channel.size());
			}
			destFile.setExecutable(srcFile.canExecute());
		}
	}

	public static class LinkFile extends ReferenceFile {

		public LinkFile(Repository repo, String path, String src, String dest) {
			super(repo, path, src, dest);
		}
	}

	public RepoProject(String name, String path, String revision,
					   String remote, Set<String> groups,
					   String recommendShallow) {
		if(name == null) {
			throw new NullPointerException();
		}
		this.name = name;
		if(path != null)
			this.path = path;
		else
			this.path = name;
		this.revision = revision;
		this.remote = remote;
		this.groups = groups;
		this.recommendShallow = recommendShallow;
		copyfiles = new ArrayList<>();
		linkfiles = new ArrayList<>();
	}

	public RepoProject(String name, String path, String revision,
					   String remote, String groupsParam) {
		this(name, path, revision, remote, new HashSet<>(), null);
		if(groupsParam != null && groupsParam.length() > 0)
			this.setGroups(groupsParam);
	}

	public RepoProject setUrl(String url) {
		this.url = url;
		return this;
	}

	public RepoProject setGroups(String groupsParam) {
		this.groups.clear();
		this.groups.addAll(Arrays.asList(groupsParam.split(",")));
		return this;
	}

	public RepoProject setDefaultRevision(String defaultRevision) {
		this.defaultRevision = defaultRevision;
		return this;
	}

	public String getName() {
		return name;
	}

	public String getPath() {
		return path;
	}

	public String getRevision() {
		return revision == null ? defaultRevision : revision;
	}

	public List<CopyFile> getCopyFiles() {
		return Collections.unmodifiableList(copyfiles);
	}

	public List<LinkFile> getLinkFiles() {
		return Collections.unmodifiableList(linkfiles);
	}

	public String getUrl() {
		return url;
	}

	public String getRemote() {
		return remote;
	}

	public boolean inGroup(String group) {
		return groups.contains(group);
	}

	public Set<String> getGroups() {
		return groups;
	}

	public String getRecommendShallow() {
		return recommendShallow;
	}

	public void setRecommendShallow(String recommendShallow) {
		this.recommendShallow = recommendShallow;
	}

	public void addCopyFile(CopyFile copyfile) {
		copyfiles.add(copyfile);
	}

	public void addCopyFiles(Collection<CopyFile> copyFiles) {
		this.copyfiles.addAll(copyFiles);
	}

	public void clearCopyFiles() {
		this.copyfiles.clear();
	}

	public void addLinkFile(LinkFile linkfile) {
		linkfiles.add(linkfile);
	}

	public void addLinkFiles(Collection<LinkFile> linkFiles) {
		this.linkfiles.addAll(linkFiles);
	}

	public void clearLinkFiles() {
		this.linkfiles.clear();
	}

	private String getPathWithSlash() {
		if(path.endsWith("/")) {
			return path;
		}
		return path + "/";
	}

	public boolean isAncestorOf(RepoProject that) {
		return isAncestorOf(that.getPathWithSlash());
	}

	public boolean isAncestorOf(String thatPath) {
		return thatPath.startsWith(getPathWithSlash());
	}

	@Override
	public boolean equals(Object o) {
		if(o instanceof RepoProject) {
			RepoProject that = (RepoProject) o;
			return this.getPathWithSlash().equals(that.getPathWithSlash());
		}
		return false;
	}

	@Override
	public int hashCode() {
		return this.getPathWithSlash().hashCode();
	}

	@Override
	public int compareTo(RepoProject that) {
		return this.getPathWithSlash().compareTo(that.getPathWithSlash());
	}
}

