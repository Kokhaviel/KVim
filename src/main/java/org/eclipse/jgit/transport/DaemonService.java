/*
 * Copyright (C) 2008-2009, Google Inc.
 * Copyright (C) 2009, Robin Rosenberg <robin.rosenberg@dewire.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import java.io.IOException;
import java.util.Collection;

import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Config.SectionParser;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;

public abstract class DaemonService {
	private final String command;
	private final SectionParser<ServiceConfig> configKey;
	private boolean enabled;
	private final boolean overridable;

	DaemonService(String cmdName, String cfgName) {
		command = cmdName.startsWith("git-") ? cmdName : "git-" + cmdName;
		configKey = cfg -> new ServiceConfig(DaemonService.this, cfg, cfgName);
		overridable = true;
	}

	private static class ServiceConfig {
		final boolean enabled;

		ServiceConfig(final DaemonService service, final Config cfg,
					  final String name) {
			enabled = cfg.getBoolean("daemon", name, service.isEnabled());
		}
	}

	public boolean isEnabled() {
		return enabled;
	}

	public boolean isOverridable() {
		return overridable;
	}

	public boolean handles(String commandLine) {
		return command.length() + 1 < commandLine.length()
				&& commandLine.charAt(command.length()) == ' '
				&& commandLine.startsWith(command);
	}

	void execute(DaemonClient client, String commandLine, @Nullable Collection<String> extraParameters)
			throws IOException, ServiceNotEnabledException, ServiceNotAuthorizedException {
		final String name = commandLine.substring(command.length() + 1);
		try(Repository db = client.getDaemon().openRepository(client, name)) {
			if(isEnabledFor(db)) {
				execute(client, db, extraParameters);
			}
		} catch(ServiceMayNotContinueException e) {
			PacketLineOut pktOut = new PacketLineOut(client.getOutputStream());
			pktOut.writeString("ERR " + e.getMessage() + "\n");
		}
	}

	private boolean isEnabledFor(Repository db) {
		if(isOverridable())
			return db.getConfig().get(configKey).enabled;
		return isEnabled();
	}

	abstract void execute(DaemonClient client, Repository db,
						  @Nullable Collection<String> extraParameters)
			throws IOException, ServiceNotEnabledException,
			ServiceNotAuthorizedException;
}
