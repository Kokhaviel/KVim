/*
 * Copyright (C) 2010, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.events;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class ListenerList {
	private final Map<Class<? extends RepositoryListener>, CopyOnWriteArrayList<ListenerHandle>> lists = new ConcurrentHashMap<>();

	public ListenerHandle addConfigChangedListener(
			ConfigChangedListener listener) {
		return addListener(ConfigChangedListener.class, listener);
	}

	public <T extends RepositoryListener> ListenerHandle addListener(
			Class<T> type, T listener) {
		ListenerHandle handle = new ListenerHandle(type, listener);
		add(handle);
		return handle;
	}

	@SuppressWarnings("unchecked")
	public void dispatch(RepositoryEvent event) {
		List<ListenerHandle> list = lists.get(event.getListenerType());
		if(list != null) {
			for(ListenerHandle handle : list)
				event.dispatch(handle.listener);
		}
	}

	private void add(ListenerHandle handle) {
		List<ListenerHandle> list = lists.get(handle.type);
		if(list == null) {
			CopyOnWriteArrayList<ListenerHandle> newList;

			newList = new CopyOnWriteArrayList<>();
			list = lists.putIfAbsent(handle.type, newList);
			if(list == null)
				list = newList;
		}
		list.add(handle);
	}

}
