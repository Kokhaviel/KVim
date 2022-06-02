/*
 * Copyright (C) 2014, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.gitrepo;

import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.GitCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRefNameException;
import org.eclipse.jgit.gitrepo.BareSuperprojectWriter.ExtraContent;
import org.eclipse.jgit.gitrepo.ManifestParser.IncludedFileReader;
import org.eclipse.jgit.gitrepo.internal.RepoText;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.FileUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.*;

public class RepoCommand extends GitCommand<RevCommit> {


	private String manifestPath;
	private String baseUri;
	private URI targetUri;
	private String groupsParam;
	private String branch;
	private PersonIdent author;
	private RemoteReader callback;
	private InputStream inputStream;
	private IncludedFileReader includedReader;

	private final BareSuperprojectWriter.BareWriterConfig bareWriterConfig = BareSuperprojectWriter.BareWriterConfig
			.getDefault();

	private ProgressMonitor monitor;

	private final List<ExtraContent> extraContents = new ArrayList<>();

	public interface RemoteReader {

		@Nullable
		ObjectId sha1(String uri, String ref) throws GitAPIException;

		@NonNull
		RemoteFile readFileWithMode(String uri, String ref, String path) throws GitAPIException, IOException;
	}

	public static final class RemoteFile {
		@NonNull
		private final byte[] contents;

		@NonNull
		private final FileMode fileMode;

		public RemoteFile(@NonNull byte[] contents,
						  @NonNull FileMode fileMode) {
			this.contents = Objects.requireNonNull(contents);
			this.fileMode = Objects.requireNonNull(fileMode);
		}

		@NonNull
		public byte[] getContents() {
			return contents;
		}

		@NonNull
		public FileMode getFileMode() {
			return fileMode;
		}

	}

	public static class DefaultRemoteReader implements RemoteReader {

		@Override
		public ObjectId sha1(String uri, String ref) throws GitAPIException {
			Map<String, Ref> map = Git
					.lsRemoteRepository()
					.setRemote(uri)
					.callAsMap();
			Ref r = RefDatabase.findRef(map, ref);
			return r != null ? r.getObjectId() : null;
		}

		@Override
		public RemoteFile readFileWithMode(String uri, String ref, String path)
				throws GitAPIException, IOException {
			File dir = FileUtils.createTempDir("jgit_", ".git", null);
			try(Git git = Git.cloneRepository().setBare(true).setDirectory(dir)
					.setURI(uri).call()) {
				Repository repo = git.getRepository();
				ObjectId refCommitId = sha1(uri, ref);
				if(refCommitId == null) {
					throw new InvalidRefNameException(MessageFormat
							.format(JGitText.get().refNotResolved, ref));
				}
				RevCommit commit = repo.parseCommit(refCommitId);
				TreeWalk tw = TreeWalk.forPath(repo, path, commit.getTree());

				return new RemoteFile(
						tw.getObjectReader().open(tw.getObjectId(0))
								.getCachedBytes(Integer.MAX_VALUE),
						tw.getFileMode(0));
			} finally {
				FileUtils.delete(dir, FileUtils.RECURSIVE);
			}
		}
	}

	static class ManifestErrorException extends GitAPIException {
		ManifestErrorException(Throwable cause) {
			super(RepoText.get().invalidManifest, cause);
		}
	}

	static class RemoteUnavailableException extends GitAPIException {
		RemoteUnavailableException(String uri) {
			super(MessageFormat.format(RepoText.get().errorRemoteUnavailable, uri));
		}
	}

	public RepoCommand(Repository repo) {
		super(repo);
	}

	@Override
	public RevCommit call() throws GitAPIException {
		checkCallable();
		if(baseUri == null) {
			baseUri = "";
		}
		if(inputStream == null) {
			if(manifestPath == null || manifestPath.length() == 0)
				throw new IllegalArgumentException(
						JGitText.get().pathNotConfigured);
			try {
				inputStream = Files.newInputStream(Paths.get(manifestPath));
			} catch(IOException e) {
				throw new IllegalArgumentException(
						JGitText.get().pathNotConfigured, e);
			}
		}

		List<RepoProject> filteredProjects;
		try {
			ManifestParser parser = new ManifestParser(includedReader,
					manifestPath, branch, baseUri, groupsParam, repo);
			parser.read(inputStream);
			filteredProjects = parser.getFilteredProjects();
		} catch(IOException e) {
			throw new ManifestErrorException(e);
		} finally {
			try {
				inputStream.close();
			} catch(IOException ignored) {

			}
		}

		if(repo.isBare()) {
			List<RepoProject> renamedProjects = renameProjects(filteredProjects);
			String targetBranch = Constants.HEAD;
			BareSuperprojectWriter writer = new BareSuperprojectWriter(repo, targetUri,
					targetBranch,
					author == null ? new PersonIdent(repo) : author,
					callback == null ? new DefaultRemoteReader() : callback,
					bareWriterConfig, extraContents);
			return writer.write(renamedProjects);
		}


		RegularSuperprojectWriter writer = new RegularSuperprojectWriter(repo, monitor);
		return writer.write(filteredProjects);
	}


	private List<RepoProject> renameProjects(List<RepoProject> projects) {
		Map<String, List<RepoProject>> m = new TreeMap<>();
		for(RepoProject proj : projects) {
			List<RepoProject> l = m.computeIfAbsent(proj.getName(), k -> new ArrayList<>());
			l.add(proj);
		}

		List<RepoProject> ret = new ArrayList<>();
		for(List<RepoProject> ps : m.values()) {
			boolean nameConflict = ps.size() != 1;
			for(RepoProject proj : ps) {
				String name = proj.getName();
				if(nameConflict) {
					name += SLASH + proj.getPath();
				}
				RepoProject p = new RepoProject(name,
						proj.getPath(), proj.getRevision(), null,
						proj.getGroups(), proj.getRecommendShallow());
				p.setUrl(proj.getUrl());
				p.addCopyFiles(proj.getCopyFiles());
				p.addLinkFiles(proj.getLinkFiles());
				ret.add(p);
			}
		}
		return ret;
	}


	private static final String SLASH = "/";

	static URI relativize(URI current, URI target) {
		if(!Objects.equals(current.getHost(), target.getHost())) {
			return target;
		}

		String cur = current.normalize().getPath();
		String dest = target.normalize().getPath();

		if(cur.startsWith(SLASH) != dest.startsWith(SLASH)) {
			return target;
		}

		while(cur.startsWith(SLASH)) {
			cur = cur.substring(1);
		}
		while(dest.startsWith(SLASH)) {
			dest = dest.substring(1);
		}

		if(cur.indexOf('/') == -1 || dest.indexOf('/') == -1) {

			String prefix = "prefix/";
			cur = prefix + cur;
			dest = prefix + dest;
		}

		if(!cur.endsWith(SLASH)) {
			int lastSlash = cur.lastIndexOf('/');
			cur = cur.substring(0, lastSlash);
		}
		String destFile = "";
		if(!dest.endsWith(SLASH)) {
			int lastSlash = dest.lastIndexOf('/');
			destFile = dest.substring(lastSlash + 1);
			dest = dest.substring(0, dest.lastIndexOf('/'));
		}

		String[] cs = cur.split(SLASH);
		String[] ds = dest.split(SLASH);

		int common = 0;
		while(common < cs.length && common < ds.length && cs[common].equals(ds[common])) {
			common++;
		}

		StringJoiner j = new StringJoiner(SLASH);
		for(int i = common; i < cs.length; i++) {
			j.add("..");
		}
		for(int i = common; i < ds.length; i++) {
			j.add(ds[i]);
		}

		j.add(destFile);
		return URI.create(j.toString());
	}

}
