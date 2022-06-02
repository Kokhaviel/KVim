/*
 * Copyright (c) 2019 Matthias Sohn <matthias.sohn@sap.com>
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.util;

import java.io.IOException;
import java.lang.management.ManagementFactory;

import javax.management.InstanceAlreadyExistsException;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectInstance;
import javax.management.ObjectName;

import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.ConfigConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Monitoring {
	private static final Logger LOG = LoggerFactory.getLogger(Monitoring.class);

	public static @Nullable ObjectInstance registerMBean(Object mbean,
														 String metricName) {
		boolean register = false;
		try {
			Class<?>[] interfaces = mbean.getClass().getInterfaces();
			for(Class<?> i : interfaces) {
				register = SystemReader.getInstance().getUserConfig()
						.getBoolean(
								ConfigConstants.CONFIG_JMX_SECTION,
								i.getSimpleName(), false);
				if(register) {
					break;
				}
			}
		} catch(IOException | ConfigInvalidException e) {
			LOG.error(e.getMessage(), e);
			return null;
		}
		if(!register) {
			return null;
		}
		MBeanServer server = ManagementFactory.getPlatformMBeanServer();
		try {
			ObjectName mbeanName = objectName(mbean.getClass(), metricName);
			if(server.isRegistered(mbeanName)) {
				server.unregisterMBean(mbeanName);
			}
			return server.registerMBean(mbean, mbeanName);
		} catch(MalformedObjectNameException | InstanceAlreadyExistsException
				| MBeanRegistrationException | NotCompliantMBeanException
				| InstanceNotFoundException e) {
			LOG.error(e.getMessage(), e);
			return null;
		}
	}

	private static ObjectName objectName(Class<?> mbean, String metricName)
			throws MalformedObjectNameException {
		return new ObjectName(String.format("org.eclipse.jgit/%s:type=%s",
				metricName, mbean.getSimpleName()));
	}
}
