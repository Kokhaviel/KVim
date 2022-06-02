/*
 * Copyright (C) 2010, 2022 Chris Aniszczyk <caniszczyk@gmail.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.api;

import org.eclipse.jgit.api.errors.*;
import org.eclipse.jgit.errors.TooLargeObjectInPackException;
import org.eclipse.jgit.errors.TooLargePackException;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.transport.*;
import org.eclipse.jgit.transport.PushConfig.PushDefault;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.text.MessageFormat;
import java.util.*;

public class PushCommand extends TransportCommand<PushCommand, Iterable<OperationResult>> {

	private String remote;
	private final List<RefSpec> refSpecs;
	private final Map<String, RefLeaseSpec> refLeaseSpecs;
	private final ProgressMonitor monitor = NullProgressMonitor.INSTANCE;
	private boolean dryRun;
	private boolean atomic;
	private boolean force;
	private OutputStream out;
	private List<String> pushOptions;
	private PushDefault pushDefault = PushDefault.CURRENT;

	protected PushCommand(Repository repo) {
		super(repo);
		refSpecs = new ArrayList<>(3);
		refLeaseSpecs = new HashMap<>();
	}

	@Override
	public Iterable<OperationResult> call() throws GitAPIException {
		checkCallable();
		setCallable(false);

		ArrayList<OperationResult> pushResults = new ArrayList<>(3);

		try {
			Config config = repo.getConfig();
			remote = determineRemote(config, remote);
			if(refSpecs.isEmpty()) {
				RemoteConfig rc = new RemoteConfig(config,
						getRemote());
				refSpecs.addAll(rc.getPushRefSpecs());
				if(refSpecs.isEmpty()) {
					determineDefaultRefSpecs(config);
				}
			}

			if(force) {
				refSpecs.replaceAll(refSpec -> refSpec.setForceUpdate(true));
			}

			List<Transport> transports = Transport.openAll(repo, remote,
					Transport.Operation.PUSH);
			for(final Transport transport : transports) {
				boolean thin = Transport.DEFAULT_PUSH_THIN;
				transport.setPushThin(thin);
				transport.setPushAtomic(atomic);
				String receivePack = RemoteConfig.DEFAULT_RECEIVE_PACK;
				transport.setOptionReceivePack(receivePack);
				transport.setDryRun(dryRun);
				transport.setPushOptions(pushOptions);
				configure(transport);

				final Collection<RemoteRefUpdate> toPush = transport
						.findRemoteRefUpdatesFor(refSpecs, refLeaseSpecs);

				try {
					OperationResult result = transport.push(monitor, toPush, out);
					pushResults.add(result);

				} catch(TooLargePackException e) {
					throw new org.eclipse.jgit.api.errors.TooLargePackException(
							e.getMessage(), e);
				} catch(TooLargeObjectInPackException e) {
					throw new org.eclipse.jgit.api.errors.TooLargeObjectInPackException(
							e.getMessage(), e);
				} catch(TransportException e) {
					throw new org.eclipse.jgit.api.errors.TransportException(
							e.getMessage(), e);
				} finally {
					transport.close();
				}
			}

		} catch(URISyntaxException e) {
			throw new InvalidRemoteException(
					MessageFormat.format(JGitText.get().invalidRemote, remote),
					e);
		} catch(TransportException e) {
			throw new org.eclipse.jgit.api.errors.TransportException(
					e.getMessage(), e);
		} catch(IOException e) {
			throw new JGitInternalException(
					JGitText.get().exceptionCaughtDuringExecutionOfPushCommand,
					e);
		}

		return pushResults;
	}

	private String determineRemote(Config config, String remoteName)
			throws IOException {
		if(remoteName != null) {
			return remoteName;
		}
		Ref head = repo.exactRef(Constants.HEAD);
		String effectiveRemote = null;
		BranchConfig branchCfg = null;
		if(head != null && head.isSymbolic()) {
			String currentBranch = head.getLeaf().getName();
			branchCfg = new BranchConfig(config,
					Repository.shortenRefName(currentBranch));
			effectiveRemote = branchCfg.getPushRemote();
		}
		if(effectiveRemote == null) {
			effectiveRemote = config.getString(
					ConfigConstants.CONFIG_REMOTE_SECTION, null,
					ConfigConstants.CONFIG_KEY_PUSH_DEFAULT);
			if(effectiveRemote == null && branchCfg != null) {
				effectiveRemote = branchCfg.getRemote();
			}
		}
		if(effectiveRemote == null) {
			effectiveRemote = Constants.DEFAULT_REMOTE_NAME;
		}
		return effectiveRemote;
	}

	private String getCurrentBranch()
			throws IOException, DetachedHeadException {
		Ref head = repo.exactRef(Constants.HEAD);
		if(head != null && head.isSymbolic()) {
			return head.getLeaf().getName();
		}
		throw new DetachedHeadException();
	}

	private void determineDefaultRefSpecs(Config config)
			throws IOException, GitAPIException {
		if(pushDefault == null) {
			pushDefault = config.get(PushConfig::new).getPushDefault();
		}
		switch(pushDefault) {
			case CURRENT:
				refSpecs.add(new RefSpec(getCurrentBranch()));
				break;
			case MATCHING:
				refSpecs.add(new RefSpec(":"));
				break;
			case NOTHING:
				throw new InvalidRefNameException(
						JGitText.get().pushDefaultNothing);
			case SIMPLE:
			case UPSTREAM:
				String currentBranch = getCurrentBranch();
				BranchConfig branchCfg = new BranchConfig(config,
						Repository.shortenRefName(currentBranch));
				String fetchRemote = branchCfg.getRemote();
				if(fetchRemote == null) {
					fetchRemote = Constants.DEFAULT_REMOTE_NAME;
				}
				boolean isTriangular = !fetchRemote.equals(remote);
				if(isTriangular) {
					if(PushDefault.UPSTREAM.equals(pushDefault)) {
						throw new InvalidRefNameException(MessageFormat.format(
								JGitText.get().pushDefaultTriangularUpstream,
								remote, fetchRemote));
					}

					refSpecs.add(new RefSpec(currentBranch));
				} else {
					String trackedBranch = branchCfg.getMerge();
					if(branchCfg.isRemoteLocal() || trackedBranch == null
							|| !trackedBranch.startsWith(Constants.R_HEADS)) {
						throw new InvalidRefNameException(MessageFormat.format(
								JGitText.get().pushDefaultNoUpstream,
								currentBranch));
					}
					if(PushDefault.SIMPLE.equals(pushDefault)
							&& !trackedBranch.equals(currentBranch)) {
						throw new InvalidRefNameException(MessageFormat.format(
								JGitText.get().pushDefaultSimple, currentBranch,
								trackedBranch));
					}
					refSpecs.add(new RefSpec(currentBranch + ':' + trackedBranch));
				}
				break;
			default:
				throw new InvalidRefNameException(MessageFormat.format(JGitText.get().pushDefaultUnknown, pushDefault));
		}
	}

	public String getRemote() {
		return remote;
	}
}
