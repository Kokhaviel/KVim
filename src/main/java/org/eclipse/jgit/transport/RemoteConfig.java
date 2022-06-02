/*
 * Copyright (C) 2009, Google Inc.
 * Copyright (C) 2008, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import java.io.Serializable;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.jgit.lib.Config;

public class RemoteConfig implements Serializable {
	private static final long serialVersionUID = 1L;

	private static final String SECTION = "remote";
	private static final String KEY_URL = "url";
	private static final String KEY_PUSHURL = "pushurl";
	private static final String KEY_FETCH = "fetch";
	private static final String KEY_PUSH = "push";
	private static final String KEY_UPLOADPACK = "uploadpack";
	private static final String KEY_RECEIVEPACK = "receivepack";
	private static final String KEY_TAGOPT = "tagopt";
	private static final String KEY_MIRROR = "mirror";
	private static final String KEY_TIMEOUT = "timeout";
	private static final boolean DEFAULT_MIRROR = false;
	public static final String DEFAULT_UPLOAD_PACK = "git-upload-pack";
	public static final String DEFAULT_RECEIVE_PACK = "git-receive-pack";

	public static List<RemoteConfig> getAllRemoteConfigs(Config rc)
			throws URISyntaxException {
		final List<String> names = new ArrayList<>(rc.getSubsections(SECTION));
		Collections.sort(names);

		final List<RemoteConfig> result = new ArrayList<>(names.size());
		for(String name : names)
			result.add(new RemoteConfig(rc, name));
		return result;
	}

	private final String name;
	private final List<URIish> uris;
	private final List<URIish> pushURIs;
	private final List<RefSpec> fetch;
	private final List<RefSpec> push;
	private final String uploadpack;
	private final String receivepack;
	private TagOpt tagopt;
	private boolean mirror;
	private int timeout;

	public RemoteConfig(Config rc, String remoteName)
			throws URISyntaxException {
		name = remoteName;

		String[] vlst;
		String val;

		vlst = rc.getStringList(SECTION, name, KEY_URL);
		UrlConfig urls = new UrlConfig(rc);
		uris = new ArrayList<>(vlst.length);
		for(String s : vlst) {
			uris.add(new URIish(urls.replace(s)));
		}
		String[] plst = rc.getStringList(SECTION, name, KEY_PUSHURL);
		pushURIs = new ArrayList<>(plst.length);
		for(String s : plst) {
			pushURIs.add(new URIish(s));
		}
		if(pushURIs.isEmpty()) {
			if(urls.hasPushReplacements()) {
				for(String s : vlst) {
					String replaced = urls.replacePush(s);
					if(!s.equals(replaced)) {
						pushURIs.add(new URIish(replaced));
					}
				}
			}
		}
		fetch = rc.getRefSpecs(SECTION, name, KEY_FETCH);
		push = rc.getRefSpecs(SECTION, name, KEY_PUSH);
		val = rc.getString(SECTION, name, KEY_UPLOADPACK);
		if(val == null) {
			val = DEFAULT_UPLOAD_PACK;
		}
		uploadpack = val;

		val = rc.getString(SECTION, name, KEY_RECEIVEPACK);
		if(val == null) {
			val = DEFAULT_RECEIVE_PACK;
		}
		receivepack = val;

		try {
			val = rc.getString(SECTION, name, KEY_TAGOPT);
			tagopt = TagOpt.fromOption(val);
		} catch(IllegalArgumentException e) {
			tagopt = TagOpt.AUTO_FOLLOW;
		}
		mirror = rc.getBoolean(SECTION, name, KEY_MIRROR, DEFAULT_MIRROR);
		timeout = rc.getInt(SECTION, name, KEY_TIMEOUT, 0);
	}

	public void update(Config rc) {
		final List<String> vlst = new ArrayList<>();

		for(URIish u : getURIs())
			vlst.add(u.toPrivateString());
		rc.setStringList(SECTION, getName(), KEY_URL, vlst);

		vlst.clear();
		for(URIish u : getPushURIs())
			vlst.add(u.toPrivateString());
		rc.setStringList(SECTION, getName(), KEY_PUSHURL, vlst);

		vlst.clear();
		for(RefSpec u : getFetchRefSpecs())
			vlst.add(u.toString());
		rc.setStringList(SECTION, getName(), KEY_FETCH, vlst);

		vlst.clear();
		for(RefSpec u : getPushRefSpecs())
			vlst.add(u.toString());
		rc.setStringList(SECTION, getName(), KEY_PUSH, vlst);

		set(rc, KEY_UPLOADPACK, getUploadPack(), DEFAULT_UPLOAD_PACK);
		set(rc, KEY_RECEIVEPACK, getReceivePack(), DEFAULT_RECEIVE_PACK);
		set(rc, KEY_TAGOPT, getTagOpt().option(), TagOpt.AUTO_FOLLOW.option());
		set(rc, mirror);
		set(rc, timeout);
	}

	private void set(final Config rc, final String key,
					 final String currentValue, final String defaultValue) {
		if(defaultValue.equals(currentValue))
			unset(rc, key);
		else
			rc.setString(SECTION, getName(), key, currentValue);
	}

	private void set(final Config rc, final boolean currentValue) {
		if(RemoteConfig.DEFAULT_MIRROR == currentValue)
			unset(rc, RemoteConfig.KEY_MIRROR);
		else
			rc.setBoolean(SECTION, getName(), RemoteConfig.KEY_MIRROR, currentValue);
	}

	private void set(final Config rc, final int currentValue) {
		if(0 == currentValue)
			unset(rc, RemoteConfig.KEY_TIMEOUT);
		else
			rc.setInt(SECTION, getName(), RemoteConfig.KEY_TIMEOUT, currentValue);
	}

	private void unset(Config rc, String key) {
		rc.unset(SECTION, getName(), key);
	}

	public String getName() {
		return name;
	}

	public List<URIish> getURIs() {
		return Collections.unmodifiableList(uris);
	}

	public boolean addURI(URIish toAdd) {
		if(uris.contains(toAdd))
			return false;
		return uris.add(toAdd);
	}

	public boolean removeURI(URIish toRemove) {
		return uris.remove(toRemove);
	}

	public List<URIish> getPushURIs() {
		return Collections.unmodifiableList(pushURIs);
	}

	public boolean addPushURI(URIish toAdd) {
		if(pushURIs.contains(toAdd))
			return false;
		return pushURIs.add(toAdd);
	}

	public boolean removePushURI(URIish toRemove) {
		return pushURIs.remove(toRemove);
	}

	public List<RefSpec> getFetchRefSpecs() {
		return Collections.unmodifiableList(fetch);
	}

	public boolean addFetchRefSpec(RefSpec s) {
		if(fetch.contains(s))
			return false;
		return fetch.add(s);
	}

	public void setFetchRefSpecs(List<RefSpec> specs) {
		fetch.clear();
		fetch.addAll(specs);
	}

	public List<RefSpec> getPushRefSpecs() {
		return Collections.unmodifiableList(push);
	}

	public String getUploadPack() {
		return uploadpack;
	}

	public String getReceivePack() {
		return receivepack;
	}

	public TagOpt getTagOpt() {
		return tagopt;
	}

	public void setTagOpt(TagOpt option) {
		tagopt = option != null ? option : TagOpt.AUTO_FOLLOW;
	}

	public void setMirror(boolean m) {
		mirror = m;
	}

	public int getTimeout() {
		return timeout;
	}

	public void setTimeout(int seconds) {
		timeout = seconds;
	}
}
