/*
 * Copyright (C) 2010, 2020 Chris Aniszczyk <caniszczyk@gmail.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.api;

import org.eclipse.jgit.api.errors.*;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.CredentialsProvider;

import java.io.IOException;
import java.text.MessageFormat;

public class TagCommand extends GitCommand<Ref> {

	private RevObject id;
	private String name;
	private String message;
	private PersonIdent tagger;
	private Boolean signed;
	private boolean forceUpdate;
	private Boolean annotated;
	private String signingKey;
	private GpgConfig gpgConfig;
	private GpgObjectSigner gpgSigner;
	private final CredentialsProvider credentialsProvider;

	protected TagCommand(Repository repo) {
		super(repo);
		this.credentialsProvider = CredentialsProvider.getDefault();
	}

	@Override
	public Ref call() throws GitAPIException {
		checkCallable();

		processOptions();

		try(RevWalk revWalk = new RevWalk(repo)) {
			if(id == null) {
				ObjectId objectId = repo.resolve(Constants.HEAD + "^{commit}");
				if(objectId == null)
					throw new NoHeadException(JGitText.get().tagOnRepoWithoutHEADCurrentlyNotSupported);

				id = revWalk.parseCommit(objectId);
			}

			if(!isAnnotated()) {
				return updateTagRef(id, revWalk, name, "SimpleTag[" + name + " : " + id + "]");
			}

			TagBuilder newTag = new TagBuilder();
			newTag.setTag(name);
			newTag.setMessage(message);
			newTag.setTagger(tagger);
			newTag.setObjectId(id);

			if(gpgSigner != null) {
				gpgSigner.signObject(newTag, signingKey, tagger, credentialsProvider, gpgConfig);
			}

			try(ObjectInserter inserter = repo.newObjectInserter()) {
				ObjectId tagId = inserter.insert(newTag);
				inserter.flush();

				String tag = newTag.getTag();
				return updateTagRef(tagId, revWalk, tag, newTag.toString());

			}

		} catch(IOException e) {
			throw new JGitInternalException(JGitText.get().exceptionCaughtDuringExecutionOfTagCommand, e);
		}
	}

	private Ref updateTagRef(ObjectId tagId, RevWalk revWalk,
							 String tagName, String newTagToString) throws IOException,
			ConcurrentRefUpdateException, RefAlreadyExistsException {
		String refName = Constants.R_TAGS + tagName;
		RefUpdate tagRef = repo.updateRef(refName);
		tagRef.setNewObjectId(tagId);
		tagRef.setForceUpdate(forceUpdate);
		tagRef.setRefLogMessage("tagged " + name, false);
		Result updateResult = tagRef.update(revWalk);
		switch(updateResult) {
			case NEW:
			case FORCED:
				return repo.exactRef(refName);
			case LOCK_FAILURE:
				throw new ConcurrentRefUpdateException(
						JGitText.get().couldNotLockHEAD, updateResult);
			case NO_CHANGE:
				if(forceUpdate) {
					return repo.exactRef(refName);
				}
				throw new RefAlreadyExistsException(MessageFormat.format(
						JGitText.get().tagAlreadyExists, newTagToString), updateResult);
			case REJECTED:
				throw new RefAlreadyExistsException(MessageFormat.format(
						JGitText.get().tagAlreadyExists, newTagToString), updateResult);
			default:
				throw new JGitInternalException(MessageFormat.format(
						JGitText.get().updatingRefFailed, refName, newTagToString, updateResult));
		}
	}

	private void processOptions()
			throws InvalidTagNameException, ServiceUnavailableException {
		if(name == null
				|| !Repository.isValidRefName(Constants.R_TAGS + name)) {
			throw new InvalidTagNameException(
					MessageFormat.format(JGitText.get().tagNameInvalid,
							name == null ? "<null>" : name));
		}
		if(!isAnnotated()) {
			if((message != null && !message.isEmpty()) || tagger != null) {
				throw new JGitInternalException(JGitText
						.get().messageAndTaggerNotAllowedInUnannotatedTags);
			}
		} else {
			if(tagger == null) {
				tagger = new PersonIdent(repo);
			}

			if(!(Boolean.FALSE.equals(signed) && signingKey == null)) {
				if(gpgConfig == null) {
					gpgConfig = new GpgConfig(repo.getConfig());
				}
				boolean doSign = isSigned() || gpgConfig.isSignAllTags();
				if(!Boolean.TRUE.equals(annotated) && !doSign) {
					doSign = gpgConfig.isSignAnnotated();
				}
				if(doSign) {
					if(signingKey == null) {
						signingKey = gpgConfig.getSigningKey();
					}
					if(gpgSigner == null) {
						GpgSigner signer = GpgSigner.getDefault();
						if(!(signer instanceof GpgObjectSigner)) {
							throw new ServiceUnavailableException(
									JGitText.get().signingServiceUnavailable);
						}
						gpgSigner = (GpgObjectSigner) signer;
					}
					if(message != null && !message.isEmpty()
							&& !message.endsWith("\n")) {
						message += '\n';
					}
				}
			}
		}
	}

	public TagCommand setName(String name) {
		checkCallable();
		this.name = name;
		return this;
	}

	public TagCommand setMessage(String message) {
		checkCallable();
		this.message = message;
		return this;
	}

	public boolean isSigned() {
		return Boolean.TRUE.equals(signed) || signingKey != null;
	}

	public TagCommand setTagger(PersonIdent tagger) {
		checkCallable();
		this.tagger = tagger;
		return this;
	}

	public boolean isAnnotated() {
		boolean setExplicitly = Boolean.TRUE.equals(annotated) || isSigned();
		if(setExplicitly) {
			return true;
		}

		return annotated == null;
	}

}
