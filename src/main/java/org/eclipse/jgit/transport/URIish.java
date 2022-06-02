/*
 * Copyright (C) 2009, Mykola Nikishov <mn@mn.com.ua>
 * Copyright (C) 2008, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org>
 * Copyright (C) 2010, Christian Halstrick <christian.halstrick@sap.com>
 * Copyright (C) 2013, Robin Stocker <robin@nibor.org>
 * Copyright (C) 2015, Patrick Steinhardt <ps@pks.im> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.Serializable;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.BitSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.util.RawParseUtils;
import org.eclipse.jgit.util.References;
import org.eclipse.jgit.util.StringUtils;

public class URIish implements Serializable {
	private static final String SCHEME_P = "([a-z][a-z\\d+-]+)://";
	private static final String OPT_USER_PWD_P = "(?:([^/:]+)(?::([^\\\\/]+))?@)?";
	private static final String HOST_P = "((?:[^\\\\/:]+)|(?:\\[[\\da-f:]+\\]))";
	private static final String OPT_PORT_P = "(?::(\\d*))?";
	private static final String USER_HOME_P = "(?:/~(?:[^\\\\/]+))";
	private static final String OPT_DRIVE_LETTER_P = "(?:[A-Za-z]:)?";
	private static final String RELATIVE_PATH_P = "(?:(?:[^\\\\/]+[\\\\/]+)*[^\\\\/]+[\\\\/]*)";
	private static final String PATH_P = "(" + OPT_DRIVE_LETTER_P + "[\\\\/]?" + RELATIVE_PATH_P + ")";

	private static final long serialVersionUID = 1L;

	private static final Pattern FULL_URI = Pattern.compile("^"
			+ SCHEME_P + "(?:" + OPT_USER_PWD_P + HOST_P + OPT_PORT_P + "("
			+ (USER_HOME_P + "?") + "(?:[\\\\/])|$))?(.+)?$");

	private static final Pattern LOCAL_FILE = Pattern.compile("^" + "([\\\\/]?" + PATH_P + ")$");

	private static final Pattern SINGLE_SLASH_FILE_URI = Pattern.compile("^"
			+ "(file):([\\\\/](?![\\\\/])" + PATH_P + ")$");

	private static final Pattern RELATIVE_SCP_URI = Pattern.compile("^"
			+ OPT_USER_PWD_P + HOST_P + ":(" + ("(?:" + USER_HOME_P + "[\\\\/])?") + RELATIVE_PATH_P + ")$");

	private static final Pattern ABSOLUTE_SCP_URI = Pattern.compile("^"
			+ OPT_USER_PWD_P + "([^\\\\/:]{2,}):([\\\\/]" + RELATIVE_PATH_P + ")$");

	private String scheme;
	private String path;
	private String rawPath;
	private String user;
	private String pass;
	private int port = -1;
	private String host;

	public URIish(String s) throws URISyntaxException {
		if(StringUtils.isEmptyOrNull(s)) {
			throw new URISyntaxException("The uri was empty or null", JGitText.get().cannotParseGitURIish);
		}
		Matcher matcher = SINGLE_SLASH_FILE_URI.matcher(s);
		if(matcher.matches()) {
			scheme = matcher.group(1);
			rawPath = cleanLeadingSlashes(matcher.group(2), scheme);
			path = unescape(rawPath);
			return;
		}
		matcher = FULL_URI.matcher(s);
		if(matcher.matches()) {
			scheme = matcher.group(1);
			user = unescape(matcher.group(2));
			pass = unescape(matcher.group(3));
			String portString = matcher.group(5);
			if("file".equals(scheme) && "".equals(portString)) {
				rawPath = cleanLeadingSlashes(
						n2e(matcher.group(4)) + ":" + portString
								+ n2e(matcher.group(6)) + n2e(matcher.group(7)),
						scheme);
			} else {
				host = unescape(matcher.group(4));
				if(portString != null && portString.length() > 0) {
					port = Integer.parseInt(portString);
				}
				rawPath = cleanLeadingSlashes(
						n2e(matcher.group(6)) + n2e(matcher.group(7)), scheme);
			}
			path = unescape(rawPath);
			return;
		}
		matcher = RELATIVE_SCP_URI.matcher(s);
		if(matcher.matches()) {
			user = matcher.group(1);
			pass = matcher.group(2);
			host = matcher.group(3);
			rawPath = matcher.group(4);
			path = rawPath;
			return;
		}
		matcher = ABSOLUTE_SCP_URI.matcher(s);
		if(matcher.matches()) {
			user = matcher.group(1);
			pass = matcher.group(2);
			host = matcher.group(3);
			rawPath = matcher.group(4);
			path = rawPath;
			return;
		}
		matcher = LOCAL_FILE.matcher(s);
		if(matcher.matches()) {
			rawPath = matcher.group(1);
			path = rawPath;
			return;
		}
		throw new URISyntaxException(s, JGitText.get().cannotParseGitURIish);
	}

	private static int parseHexByte(byte c1, byte c2) {
		return ((RawParseUtils.parseHexInt4(c1) << 4)
				| RawParseUtils.parseHexInt4(c2));
	}

	private static String unescape(String s) throws URISyntaxException {
		if(s == null)
			return null;
		if(s.indexOf('%') < 0)
			return s;

		byte[] bytes = s.getBytes(UTF_8);

		byte[] os = new byte[bytes.length];
		int j = 0;
		for(int i = 0; i < bytes.length; ++i) {
			byte c = bytes[i];
			if(c == '%') {
				if(i + 2 >= bytes.length)
					throw new URISyntaxException(s, JGitText.get().cannotParseGitURIish);
				byte c1 = bytes[i + 1];
				byte c2 = bytes[i + 2];
				int val;
				try {
					val = parseHexByte(c1, c2);
				} catch(ArrayIndexOutOfBoundsException e) {
					URISyntaxException use = new URISyntaxException(s,
							JGitText.get().cannotParseGitURIish);
					use.initCause(e);
					throw use;
				}
				os[j++] = (byte) val;
				i += 2;
			} else
				os[j++] = c;
		}
		return RawParseUtils.decode(os, 0, j);
	}

	private static final BitSet reservedChars = new BitSet(127);

	static {
		for(byte b : Constants.encodeASCII("!*'();:@&=+$,/?#[]"))
			reservedChars.set(b);
	}

	private static String escape(String s, boolean escapeReservedChars) {
		if(s == null)
			return null;
		ByteArrayOutputStream os = new ByteArrayOutputStream(s.length());
		byte[] bytes = s.getBytes(UTF_8);
		for(byte c : bytes) {
			int b = c & 0xFF;
			if(b <= 32 || b == '%'
					|| (escapeReservedChars && reservedChars.get(b))) {
				os.write('%');
				byte[] tmp = Constants.encodeASCII(String.format("%02x",
						b));
				os.write(tmp[0]);
				os.write(tmp[1]);
			} else {
				os.write(b);
			}
		}
		byte[] buf = os.toByteArray();
		return RawParseUtils.decode(buf, 0, buf.length);
	}

	private String n2e(String s) {
		return s == null ? "" : s;
	}

	private String cleanLeadingSlashes(String p, String s) {
		if(p.length() >= 3
				&& p.charAt(0) == '/'
				&& p.charAt(2) == ':'
				&& ((p.charAt(1) >= 'A' && p.charAt(1) <= 'Z')
				|| (p.charAt(1) >= 'a' && p.charAt(1) <= 'z')))
			return p.substring(1);
		else if(s != null && p.length() >= 2 && p.charAt(0) == '/'
				&& p.charAt(1) == '~')
			return p.substring(1);
		else
			return p;
	}

	public URIish(URL u) {
		scheme = u.getProtocol();
		path = u.getPath();
		path = cleanLeadingSlashes(path, scheme);
		try {
			rawPath = u.toURI().getRawPath();
			rawPath = cleanLeadingSlashes(rawPath, scheme);
		} catch(URISyntaxException e) {
			throw new RuntimeException(e);
		}

		final String ui = u.getUserInfo();
		if(ui != null) {
			final int d = ui.indexOf(':');
			user = d < 0 ? ui : ui.substring(0, d);
			pass = d < 0 ? null : ui.substring(d + 1);
		}

		port = u.getPort();
		host = u.getHost();
	}

	public URIish() {
	}

	private URIish(URIish u) {
		this.scheme = u.scheme;
		this.rawPath = u.rawPath;
		this.path = u.path;
		this.user = u.user;
		this.pass = u.pass;
		this.port = u.port;
		this.host = u.host;
	}

	public boolean isRemote() {
		return getHost() != null;
	}

	public String getHost() {
		return host;
	}

	public URIish setHost(String n) {
		final URIish r = new URIish(this);
		r.host = n;
		return r;
	}

	public String getScheme() {
		return scheme;
	}

	public URIish setScheme(String n) {
		final URIish r = new URIish(this);
		r.scheme = n;
		return r;
	}

	public String getPath() {
		return path;
	}

	public String getRawPath() {
		return rawPath;
	}

	public URIish setPath(String n) {
		final URIish r = new URIish(this);
		r.path = n;
		r.rawPath = n;
		return r;
	}

	public String getUser() {
		return user;
	}

	public String getPass() {
		return pass;
	}

	public URIish setPass(String n) {
		final URIish r = new URIish(this);
		r.pass = n;
		return r;
	}

	public int getPort() {
		return port;
	}

	@Override
	public int hashCode() {
		int hc = 0;
		if(getScheme() != null)
			hc = hc * 31 + getScheme().hashCode();
		if(getUser() != null)
			hc = hc * 31 + getUser().hashCode();
		if(getPass() != null)
			hc = hc * 31 + getPass().hashCode();
		if(getHost() != null)
			hc = hc * 31 + getHost().hashCode();
		if(getPort() > 0)
			hc = hc * 31 + getPort();
		if(getPath() != null)
			hc = hc * 31 + getPath().hashCode();
		return hc;
	}

	@Override
	public boolean equals(Object obj) {
		if(!(obj instanceof URIish))
			return false;
		final URIish b = (URIish) obj;
		if(!eq(getScheme(), b.getScheme()))
			return false;
		if(!eq(getUser(), b.getUser()))
			return false;
		if(!eq(getPass(), b.getPass()))
			return false;
		if(!eq(getHost(), b.getHost()))
			return false;
		if(getPort() != b.getPort())
			return false;
		return eq(getPath(), b.getPath());
	}

	private static boolean eq(String a, String b) {
		if(References.isSameObject(a, b)) {
			return true;
		}
		if(StringUtils.isEmptyOrNull(a) && StringUtils.isEmptyOrNull(b))
			return true;
		if(a == null || b == null)
			return false;
		return a.equals(b);
	}

	public String toPrivateString() {
		return format(true);
	}

	@Override
	public String toString() {
		return format(false);
	}

	private String format(boolean includePassword) {
		final StringBuilder r = new StringBuilder();
		if(getScheme() != null) {
			r.append(getScheme());
			r.append("://");
		}

		if(getUser() != null) {
			r.append(escape(getUser(), true));
			if(includePassword && getPass() != null) {
				r.append(':');
				r.append(escape(getPass(), true));
			}
		}

		if(getHost() != null) {
			if(getUser() != null && getUser().length() > 0)
				r.append('@');
			r.append(escape(getHost(), false));
			if(getScheme() != null && getPort() > 0) {
				r.append(':');
				r.append(getPort());
			}
		}

		if(getPath() != null) {
			if(getScheme() != null) {
				if(!getPath().startsWith("/") && !getPath().isEmpty())
					r.append('/');
			} else if(getHost() != null)
				r.append(':');
			if(getScheme() != null)
				r.append(getRawPath());
			else
				r.append(getPath());
		}

		return r.toString();
	}

	public String getHumanishName() throws IllegalArgumentException {
		String s = getPath();
		if("/".equals(s) || "".equals(s))
			s = getHost();
		if(s == null)
			throw new IllegalArgumentException();

		String[] elements;
		if("file".equals(scheme) || LOCAL_FILE.matcher(s).matches())
			elements = s.split("[\\\\" + File.separatorChar + "/]");
		else
			elements = s.split("/+");
		if(elements.length == 0)
			throw new IllegalArgumentException();
		String result = elements[elements.length - 1];
		if(Constants.DOT_GIT.equals(result))
			result = elements[elements.length - 2];
		else if(result.endsWith(Constants.DOT_GIT_EXT))
			result = result.substring(0, result.length()
					- Constants.DOT_GIT_EXT.length());
		if(("file".equals(scheme) || LOCAL_FILE.matcher(s)
				.matches())
				&& result.endsWith(Constants.DOT_BUNDLE_EXT)) {
			result = result.substring(0,
					result.length() - Constants.DOT_BUNDLE_EXT.length());
		}
		return result;
	}

}
