/*
 * Copyright (C) 2008, 2010, Google Inc.
 * Copyright (C) 2017, Thomas Wolf <thomas.wolf@paranor.ch> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.util.StringUtils;
import org.eclipse.jgit.util.SystemReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class HttpConfig {

	private static final Logger LOG = LoggerFactory.getLogger(HttpConfig.class);
	private static final String FTP = "ftp";
	public static final String HTTP = "http";
	public static final String FOLLOW_REDIRECTS_KEY = "followRedirects";
	public static final String MAX_REDIRECTS_KEY = "maxRedirects";
	public static final String POST_BUFFER_KEY = "postBuffer";
	public static final String SSL_VERIFY_KEY = "sslVerify";
	public static final String USER_AGENT = "userAgent";
	public static final String EXTRA_HEADER = "extraHeader";
	public static final String COOKIE_FILE_KEY = "cookieFile";
	public static final String SAVE_COOKIES_KEY = "saveCookies";
	public static final String COOKIE_FILE_CACHE_LIMIT_KEY = "cookieFileCacheLimit";
	private static final int DEFAULT_COOKIE_FILE_CACHE_LIMIT = 10;
	private static final String MAX_REDIRECT_SYSTEM_PROPERTY = "http.maxRedirects";
	private static final int DEFAULT_MAX_REDIRECTS = 5;

	private static final int MAX_REDIRECTS;

	static {
		String rawValue = SystemReader.getInstance()
				.getProperty(MAX_REDIRECT_SYSTEM_PROPERTY);
		Integer value = DEFAULT_MAX_REDIRECTS;
		if(rawValue != null) {
			try {
				value = Integer.parseUnsignedInt(rawValue);
			} catch(NumberFormatException e) {
				LOG.warn(MessageFormat.format(
						JGitText.get().invalidSystemProperty,
						MAX_REDIRECT_SYSTEM_PROPERTY, rawValue, value));
			}
		}
		MAX_REDIRECTS = value;
	}

	private static final String ENV_HTTP_USER_AGENT = "GIT_HTTP_USER_AGENT";

	public enum HttpRedirectMode implements Config.ConfigEnum {

		TRUE("true"),
		INITIAL("initial"),
		FALSE("false");

		private final String configValue;

		HttpRedirectMode(String configValue) {
			this.configValue = configValue;
		}

		@Override
		public String toConfigValue() {
			return configValue;
		}

		@Override
		public boolean matchConfigValue(String s) {
			return configValue.equals(s);
		}
	}

	private int postBuffer;
	private boolean sslVerify;
	private HttpRedirectMode followRedirects;
	private int maxRedirects;
	private String userAgent;
	private List<String> extraHeaders;
	private String cookieFile;
	private boolean saveCookies;
	private int cookieFileCacheLimit;

	public int getPostBuffer() {
		return postBuffer;
	}

	public boolean isSslVerify() {
		return sslVerify;
	}

	public HttpRedirectMode getFollowRedirects() {
		return followRedirects;
	}

	public int getMaxRedirects() {
		return maxRedirects;
	}

	public String getUserAgent() {
		return userAgent;
	}

	@NonNull
	public List<String> getExtraHeaders() {
		return extraHeaders == null ? Collections.emptyList() : extraHeaders;
	}

	public String getCookieFile() {
		return cookieFile;
	}

	public boolean getSaveCookies() {
		return saveCookies;
	}

	public int getCookieFileCacheLimit() {
		return cookieFileCacheLimit;
	}

	public HttpConfig(Config config, URIish uri) {
		init(config, uri);
	}

	public HttpConfig(URIish uri) {
		StoredConfig userConfig;
		try {
			userConfig = SystemReader.getInstance().getUserConfig();
		} catch(IOException | ConfigInvalidException e) {
			LOG.error(e.getMessage(), e);
			init(new Config(), uri);
			return;
		}
		init(userConfig, uri);
	}

	private void init(Config config, URIish uri) {
		int postBufferSize = config.getInt(HTTP, POST_BUFFER_KEY,
				1024 * 1024);
		boolean sslVerifyFlag = config.getBoolean(HTTP, SSL_VERIFY_KEY, true);
		HttpRedirectMode followRedirectsMode = config.getEnum(
				HttpRedirectMode.values(), HTTP, null,
				FOLLOW_REDIRECTS_KEY, HttpRedirectMode.INITIAL);
		int redirectLimit = config.getInt(HTTP, MAX_REDIRECTS_KEY,
				MAX_REDIRECTS);
		if(redirectLimit < 0) {
			redirectLimit = MAX_REDIRECTS;
		}
		String agent = config.getString(HTTP, null, USER_AGENT);
		if(agent != null) {
			agent = UserAgent.clean(agent);
		}
		userAgent = agent;
		String[] headers = config.getStringList(HTTP, null, EXTRA_HEADER);
		int start = findLastEmpty(headers) + 1;
		if(start > 0) {
			headers = Arrays.copyOfRange(headers, start, headers.length);
		}
		extraHeaders = Arrays.asList(headers);
		cookieFile = config.getString(HTTP, null, COOKIE_FILE_KEY);
		saveCookies = config.getBoolean(HTTP, SAVE_COOKIES_KEY, false);
		cookieFileCacheLimit = config.getInt(HTTP, COOKIE_FILE_CACHE_LIMIT_KEY,
				DEFAULT_COOKIE_FILE_CACHE_LIMIT);
		String match = findMatch(config.getSubsections(HTTP), uri);

		if(match != null) {
			postBufferSize = config.getInt(HTTP, match, POST_BUFFER_KEY,
					postBufferSize);
			sslVerifyFlag = config.getBoolean(HTTP, match, SSL_VERIFY_KEY,
					sslVerifyFlag);
			followRedirectsMode = config.getEnum(HttpRedirectMode.values(),
					HTTP, match, FOLLOW_REDIRECTS_KEY, followRedirectsMode);
			int newMaxRedirects = config.getInt(HTTP, match, MAX_REDIRECTS_KEY,
					redirectLimit);
			if(newMaxRedirects >= 0) {
				redirectLimit = newMaxRedirects;
			}
			String uriSpecificUserAgent = config.getString(HTTP, match,
					USER_AGENT);
			if(uriSpecificUserAgent != null) {
				userAgent = UserAgent.clean(uriSpecificUserAgent);
			}
			String[] uriSpecificExtraHeaders = config.getStringList(HTTP, match,
					EXTRA_HEADER);
			if(uriSpecificExtraHeaders.length > 0) {
				start = findLastEmpty(uriSpecificExtraHeaders) + 1;
				if(start > 0) {
					uriSpecificExtraHeaders = Arrays.copyOfRange(
							uriSpecificExtraHeaders, start,
							uriSpecificExtraHeaders.length);
				}
				extraHeaders = Arrays.asList(uriSpecificExtraHeaders);
			}
			String urlSpecificCookieFile = config.getString(HTTP, match,
					COOKIE_FILE_KEY);
			if(urlSpecificCookieFile != null) {
				cookieFile = urlSpecificCookieFile;
			}
			saveCookies = config.getBoolean(HTTP, match, SAVE_COOKIES_KEY,
					saveCookies);
		}
		agent = SystemReader.getInstance().getenv(ENV_HTTP_USER_AGENT);
		if(!StringUtils.isEmptyOrNull(agent)) {
			userAgent = UserAgent.clean(agent);
		}
		postBuffer = postBufferSize;
		sslVerify = sslVerifyFlag;
		followRedirects = followRedirectsMode;
		maxRedirects = redirectLimit;
	}

	private int findLastEmpty(String[] values) {
		for(int i = values.length - 1; i >= 0; i--) {
			if(values[i] == null) {
				return i;
			}
		}
		return -1;
	}

	private String findMatch(Set<String> names, URIish uri) {
		String bestMatch = null;
		int bestMatchLength = -1;
		boolean withUser = false;
		String uPath = uri.getPath();
		boolean hasPath = !StringUtils.isEmptyOrNull(uPath);
		if(hasPath) {
			uPath = normalize(uPath);
			if(uPath == null) {
				return null;
			}
		}
		for(String s : names) {
			try {
				URIish candidate = new URIish(s);
				if(!compare(uri.getScheme(), candidate.getScheme())
						|| !compare(uri.getHost(), candidate.getHost())) {
					continue;
				}
				if(defaultedPort(uri.getPort(),
						uri.getScheme()) != defaultedPort(candidate.getPort(),
						candidate.getScheme())) {
					continue;
				}
				boolean hasUser = false;
				if(candidate.getUser() != null) {
					if(!candidate.getUser().equals(uri.getUser())) {
						continue;
					}
					hasUser = true;
				}
				String cPath = candidate.getPath();
				int matchLength;
				if(StringUtils.isEmptyOrNull(cPath)) {
					matchLength = 0;
				} else {
					if(!hasPath) {
						continue;
					}
					matchLength = segmentCompare(uPath, cPath);
					if(matchLength < 0) {
						continue;
					}
				}
				if(matchLength > bestMatchLength || !withUser && hasUser && matchLength == bestMatchLength) {
					bestMatch = s;
					bestMatchLength = matchLength;
					withUser = hasUser;
				}
			} catch(URISyntaxException e) {
				LOG.warn(MessageFormat
						.format(JGitText.get().httpConfigInvalidURL, s));
			}
		}
		return bestMatch;
	}

	private boolean compare(String a, String b) {
		if(a == null) {
			return b == null;
		}
		return a.equalsIgnoreCase(b);
	}

	private int defaultedPort(int port, String scheme) {
		if(port >= 0) {
			return port;
		}
		if(FTP.equalsIgnoreCase(scheme)) {
			return 21;
		} else if(HTTP.equalsIgnoreCase(scheme)) {
			return 80;
		} else {
			return 443;
		}
	}

	static int segmentCompare(String uriPath, String m) {
		String matchPath = normalize(m);
		if(matchPath == null || !uriPath.startsWith(matchPath)) {
			return -1;
		}

		int uLength = uriPath.length();
		int mLength = matchPath.length();
		if(mLength == uLength || matchPath.charAt(mLength - 1) == '/'
				|| (mLength < uLength && uriPath.charAt(mLength) == '/')) {
			return mLength;
		}
		return -1;
	}

	static String normalize(String path) {
		int i = 0;
		int length = path.length();
		StringBuilder builder = new StringBuilder(length);
		builder.append('/');
		if(length > 0 && path.charAt(0) == '/') {
			i = 1;
		}
		while(i < length) {
			int slash = path.indexOf('/', i);
			if(slash < 0) {
				slash = length;
			}
			if(slash == i + 2 && path.charAt(i) == '.'
					&& path.charAt(i + 1) == '.') {
				int l = builder.length() - 2;
				while(l >= 0 && builder.charAt(l) != '/') {
					l--;
				}
				if(l < 0) {
					LOG.warn(MessageFormat.format(
							JGitText.get().httpConfigCannotNormalizeURL, path));
					return null;
				}
				builder.setLength(l + 1);
			} else {
				builder.append(path, i, Math.min(length, slash + 1));
			}
			i = slash + 1;
		}
		if(builder.length() > 1 && builder.charAt(builder.length() - 1) == '/'
				&& length > 0 && path.charAt(length - 1) != '/') {
			builder.setLength(builder.length() - 1);
		}
		return builder.toString();
	}
}
