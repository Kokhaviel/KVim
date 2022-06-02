/*
 * Copyright (C) 2021, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.gitrepo;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.jgit.lib.Constants.R_TAGS;

import java.io.IOException;
import java.net.URI;
import java.text.MessageFormat;
import java.util.List;

import org.eclipse.jgit.api.errors.ConcurrentRefUpdateException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.gitrepo.RepoCommand.ManifestErrorException;
import org.eclipse.jgit.gitrepo.RepoCommand.RemoteFile;
import org.eclipse.jgit.gitrepo.RepoCommand.RemoteReader;
import org.eclipse.jgit.gitrepo.RepoCommand.RemoteUnavailableException;
import org.eclipse.jgit.gitrepo.RepoProject.CopyFile;
import org.eclipse.jgit.gitrepo.RepoProject.LinkFile;
import org.eclipse.jgit.gitrepo.internal.RepoText;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.util.FileUtils;

class BareSuperprojectWriter {
	private static final int LOCK_FAILURE_MAX_RETRIES = 5;
	private static final int LOCK_FAILURE_MIN_RETRY_DELAY_MILLIS = 50;
	private static final int LOCK_FAILURE_MAX_RETRY_DELAY_MILLIS = 5000;

	private final Repository repo;
	private final URI targetUri;
	private final String targetBranch;
	private final RemoteReader callback;
	private final BareWriterConfig config;
	private final PersonIdent author;
	private final List<ExtraContent> extraContents;

	static class BareWriterConfig {
		boolean ignoreRemoteFailures = false;
		boolean recordRemoteBranch = true;
		boolean recordSubmoduleLabels = true;
		boolean recordShallowSubmodules = true;

		static BareWriterConfig getDefault() {
			return new BareWriterConfig();
		}

		private BareWriterConfig() {
		}
	}

	static class ExtraContent {
		final String path;

		final String content;

		ExtraContent(String path, String content) {
			this.path = path;
			this.content = content;
		}
	}

	BareSuperprojectWriter(Repository repo, URI targetUri,
						   String targetBranch,
						   PersonIdent author, RemoteReader callback,
						   BareWriterConfig config,
						   List<ExtraContent> extraContents) {
		assert (repo.isBare());
		this.repo = repo;
		this.targetUri = targetUri;
		this.targetBranch = targetBranch;
		this.author = author;
		this.callback = callback;
		this.config = config;
		this.extraContents = extraContents;
	}

	RevCommit write(List<RepoProject> repoProjects)
			throws GitAPIException {
		DirCache index = DirCache.newInCore();
		ObjectInserter inserter = repo.newObjectInserter();

		try(RevWalk rw = new RevWalk(repo)) {
			prepareIndex(repoProjects, index, inserter);
			ObjectId treeId = index.writeTree(inserter);
			long prevDelay = 0;
			for(int i = 0; i < LOCK_FAILURE_MAX_RETRIES - 1; i++) {
				try {
					return commitTreeOnCurrentTip(inserter, rw, treeId);
				} catch(ConcurrentRefUpdateException e) {
					prevDelay = FileUtils.delay(prevDelay,
							LOCK_FAILURE_MIN_RETRY_DELAY_MILLIS,
							LOCK_FAILURE_MAX_RETRY_DELAY_MILLIS);
					Thread.sleep(prevDelay);
					repo.getRefDatabase().refresh();
				}
			}

			return commitTreeOnCurrentTip(inserter, rw, treeId);
		} catch(IOException | InterruptedException e) {
			throw new ManifestErrorException(e);
		}
	}

	private void prepareIndex(List<RepoProject> projects, DirCache index,
							  ObjectInserter inserter) throws IOException, GitAPIException {
		Config cfg = new Config();
		StringBuilder attributes = new StringBuilder();
		DirCacheBuilder builder = index.builder();
		for(RepoProject proj : projects) {
			String name = proj.getName();
			String path = proj.getPath();
			String url = proj.getUrl();
			ObjectId objectId;
			if(ObjectId.isId(proj.getRevision())) {
				objectId = ObjectId.fromString(proj.getRevision());
			} else {
				objectId = callback.sha1(url, proj.getRevision());
				if(objectId == null && !config.ignoreRemoteFailures) {
					throw new RemoteUnavailableException(url);
				}
				if(config.recordRemoteBranch) {
					String field = proj.getRevision().startsWith(R_TAGS) ? "ref" : "branch";
					cfg.setString("submodule", name, field, proj.getRevision());
				}

				if(config.recordShallowSubmodules
						&& proj.getRecommendShallow() != null) {
					cfg.setBoolean("submodule", name, "shallow",
							true);
				}
			}
			if(config.recordSubmoduleLabels) {
				StringBuilder rec = new StringBuilder();
				rec.append("/");
				rec.append(path);
				for(String group : proj.getGroups()) {
					rec.append(" ");
					rec.append(group);
				}
				rec.append("\n");
				attributes.append(rec);
			}

			URI submodUrl = URI.create(url);
			if(targetUri != null) {
				submodUrl = RepoCommand.relativize(targetUri, submodUrl);
			}
			cfg.setString("submodule", name, "path", path);
			cfg.setString("submodule", name, "url", submodUrl.toString());

			if(objectId != null) {
				DirCacheEntry dcEntry = new DirCacheEntry(path);
				dcEntry.setObjectId(objectId);
				dcEntry.setFileMode(FileMode.GITLINK);
				builder.add(dcEntry);

				for(CopyFile copyfile : proj.getCopyFiles()) {
					RemoteFile rf = callback.readFileWithMode(url,
							proj.getRevision(), copyfile.src);
					objectId = inserter.insert(Constants.OBJ_BLOB,
							rf.getContents());
					dcEntry = new DirCacheEntry(copyfile.dest);
					dcEntry.setObjectId(objectId);
					dcEntry.setFileMode(rf.getFileMode());
					builder.add(dcEntry);
				}
				for(LinkFile linkfile : proj.getLinkFiles()) {
					String link;
					if(linkfile.dest.contains("/")) {
						link = FileUtils.relativizeGitPath(
								linkfile.dest.substring(0,
										linkfile.dest.lastIndexOf('/')),
								proj.getPath() + "/" + linkfile.src);
					} else {
						link = proj.getPath() + "/" + linkfile.src;
					}

					objectId = inserter.insert(Constants.OBJ_BLOB,
							link.getBytes(UTF_8));
					dcEntry = new DirCacheEntry(linkfile.dest);
					dcEntry.setObjectId(objectId);
					dcEntry.setFileMode(FileMode.SYMLINK);
					builder.add(dcEntry);
				}
			}
		}
		String content = cfg.toText();

		DirCacheEntry dcEntry = new DirCacheEntry(
				Constants.DOT_GIT_MODULES);
		ObjectId objectId = inserter.insert(Constants.OBJ_BLOB,
				content.getBytes(UTF_8));
		dcEntry.setObjectId(objectId);
		dcEntry.setFileMode(FileMode.REGULAR_FILE);
		builder.add(dcEntry);

		if(config.recordSubmoduleLabels) {
			DirCacheEntry dcEntryAttr = new DirCacheEntry(Constants.DOT_GIT_ATTRIBUTES);
			ObjectId attrId = inserter.insert(Constants.OBJ_BLOB, attributes.toString().getBytes(UTF_8));
			dcEntryAttr.setObjectId(attrId);
			dcEntryAttr.setFileMode(FileMode.REGULAR_FILE);
			builder.add(dcEntryAttr);
		}

		for(ExtraContent ec : extraContents) {
			DirCacheEntry extraDcEntry = new DirCacheEntry(ec.path);

			ObjectId oid = inserter.insert(Constants.OBJ_BLOB,
					ec.content.getBytes(UTF_8));
			extraDcEntry.setObjectId(oid);
			extraDcEntry.setFileMode(FileMode.REGULAR_FILE);
			builder.add(extraDcEntry);
		}

		builder.finish();
	}

	private RevCommit commitTreeOnCurrentTip(ObjectInserter inserter,
											 RevWalk rw, ObjectId treeId)
			throws IOException, ConcurrentRefUpdateException {
		ObjectId headId = repo.resolve(targetBranch + "^{commit}");
		if(headId != null && rw.parseCommit(headId).getTree().getId().equals(treeId)) {
			return rw.parseCommit(headId);
		}

		CommitBuilder commit = new CommitBuilder();
		commit.setTreeId(treeId);
		if(headId != null) {
			commit.setParentIds(headId);
		}
		commit.setAuthor(author);
		commit.setCommitter(author);
		commit.setMessage(RepoText.get().repoCommitMessage);

		ObjectId commitId = inserter.insert(commit);
		inserter.flush();

		RefUpdate ru = repo.updateRef(targetBranch);
		ru.setNewObjectId(commitId);
		ru.setExpectedOldObjectId(headId != null ? headId : ObjectId.zeroId());
		Result rc = ru.update(rw);
		switch(rc) {
			case NEW:
			case FORCED:
			case FAST_FORWARD:
				break;
			case REJECTED:
			case LOCK_FAILURE:
				throw new ConcurrentRefUpdateException(MessageFormat.format(
						JGitText.get().cannotLock, targetBranch), rc);
			default:
				throw new JGitInternalException(
						MessageFormat.format(JGitText.get().updatingRefFailed,
								targetBranch, commitId.name(), rc));
		}

		return rw.parseCommit(commitId);
	}
}
