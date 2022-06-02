/*
 * Copyright (C) 2010, 2013, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.jgit.util.HttpSupport.HDR_AUTHORIZATION;
import static org.eclipse.jgit.util.HttpSupport.HDR_WWW_AUTHENTICATE;

import java.io.IOException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.util.Base64;
import org.eclipse.jgit.util.GSSManagerFactory;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.Oid;

abstract class HttpAuthMethod {

	public enum Type {
		NONE {
			@Override
			public HttpAuthMethod method(String hdr) {
				return None.INSTANCE;
			}

			@Override
			public String getSchemeName() {
				return "None";
			}
		},
		BASIC {
			@Override
			public HttpAuthMethod method(String hdr) {
				return new Basic();
			}

			@Override
			public String getSchemeName() {
				return "Basic";
			}
		},
		DIGEST {
			@Override
			public HttpAuthMethod method(String hdr) {
				return new Digest(hdr);
			}

			@Override
			public String getSchemeName() {
				return "Digest";
			}
		},
		NEGOTIATE {
			@Override
			public HttpAuthMethod method(String hdr) {
				return new Negotiate(hdr);
			}

			@Override
			public String getSchemeName() {
				return "Negotiate";
			}
		};

		public abstract HttpAuthMethod method(String hdr);

		public abstract String getSchemeName();
	}

	static final String EMPTY_STRING = "";
	static final String SCHEMA_NAME_SEPARATOR = " ";

	static HttpAuthMethod scanResponse(final HttpConnection conn,
									   Collection<Type> ignoreTypes) {
		final Map<String, List<String>> headers = conn.getHeaderFields();
		HttpAuthMethod authentication = Type.NONE.method(EMPTY_STRING);

		for(Entry<String, List<String>> entry : headers.entrySet()) {
			if(HDR_WWW_AUTHENTICATE.equalsIgnoreCase(entry.getKey())) {
				if(entry.getValue() != null) {
					for(String value : entry.getValue()) {
						if(value != null && value.length() != 0) {
							final String[] valuePart = value.split(
									SCHEMA_NAME_SEPARATOR, 2);

							try {
								Type methodType = Type.valueOf(
										valuePart[0].toUpperCase(Locale.ROOT));

								if((ignoreTypes != null)
										&& (ignoreTypes.contains(methodType))) {
									continue;
								}

								if(authentication.getType().compareTo(methodType) >= 0) {
									continue;
								}

								final String param;
								if(valuePart.length == 1)
									param = EMPTY_STRING;
								else
									param = valuePart[1];

								authentication = methodType
										.method(param);
							} catch(IllegalArgumentException ignored) {
							}
						}
					}
				}
				break;
			}
		}

		return authentication;
	}

	protected final Type type;

	protected HttpAuthMethod(Type type) {
		this.type = type;
	}

	boolean authorize(URIish uri, CredentialsProvider credentialsProvider) {
		String username;
		String password;

		if(credentialsProvider != null) {
			CredentialItem.Username u = new CredentialItem.Username();
			CredentialItem.Password p = new CredentialItem.Password();

			if(credentialsProvider.supports(u, p)
					&& credentialsProvider.get(uri, u, p)) {
				username = u.getValue();
				char[] v = p.getValue();
				password = (v == null) ? null : new String(p.getValue());
				p.clear();
			} else
				return false;
		} else {
			username = uri.getUser();
			password = uri.getPass();
		}
		if(username != null) {
			authorize(username, password);
			return true;
		}
		return false;
	}

	abstract void authorize(String user, String pass);

	abstract void configureRequest(HttpConnection conn) throws IOException;

	public Type getType() {
		return type;
	}

	private static class None extends HttpAuthMethod {
		static final None INSTANCE = new None();

		public None() {
			super(Type.NONE);
		}

		@Override
		void authorize(String user, String pass) {
		}

		@Override
		void configureRequest(HttpConnection conn) {
		}
	}

	private static class Basic extends HttpAuthMethod {
		private String user;

		private String pass;

		public Basic() {
			super(Type.BASIC);
		}

		@Override
		void authorize(String username, String password) {
			this.user = username;
			this.pass = password;
		}

		@Override
		void configureRequest(HttpConnection conn) {
			String ident = user + ":" + pass;
			String enc = Base64.encodeBytes(ident.getBytes(UTF_8));
			conn.setRequestProperty(HDR_AUTHORIZATION, type.getSchemeName()
					+ " " + enc);
		}
	}

	private static class Digest extends HttpAuthMethod {
		private static final SecureRandom PRNG = new SecureRandom();
		private final Map<String, String> params;
		private int requestCount;
		private String user;
		private String pass;

		Digest(String hdr) {
			super(Type.DIGEST);
			params = parse(hdr);

			final String qop = params.get("qop");
			if("auth".equals(qop)) {
				final byte[] bin = new byte[8];
				PRNG.nextBytes(bin);
				params.put("cnonce", Base64.encodeBytes(bin));
			}
		}

		@Override
		void authorize(String username, String password) {
			this.user = username;
			this.pass = password;
		}

		@SuppressWarnings("boxing")
		@Override
		void configureRequest(HttpConnection conn) {
			final Map<String, String> r = new LinkedHashMap<>();

			final String realm = params.get("realm");
			final String nonce = params.get("nonce");
			final String cnonce = params.get("cnonce");
			final String uri = uri(conn.getURL());
			final String qop = params.get("qop");
			final String method = conn.getRequestMethod();

			final String A1 = user + ":" + realm + ":" + pass;
			final String A2 = method + ":" + uri;

			r.put("username", user);
			r.put("realm", realm);
			r.put("nonce", nonce);
			r.put("uri", uri);

			final String response, nc;
			if("auth".equals(qop)) {
				nc = String.format("%08x", ++requestCount);
				response = KD(H(A1), nonce + ":" + nc + ":" + cnonce + ":"
						+ qop + ":"
						+ H(A2));
			} else {
				nc = null;
				response = KD(H(A1), nonce + ":" + H(A2));
			}
			r.put("response", response);
			if(params.containsKey("algorithm"))
				r.put("algorithm", "MD5");
			if(cnonce != null && qop != null)
				r.put("cnonce", cnonce);
			if(params.containsKey("opaque"))
				r.put("opaque", params.get("opaque"));
			if(qop != null)
				r.put("qop", qop);
			if(nc != null)
				r.put("nc", nc);

			StringBuilder v = new StringBuilder();
			for(Map.Entry<String, String> e : r.entrySet()) {
				if(v.length() > 0)
					v.append(", ");
				v.append(e.getKey());
				v.append('=');
				v.append('"');
				v.append(e.getValue());
				v.append('"');
			}
			conn.setRequestProperty(HDR_AUTHORIZATION, type.getSchemeName()
					+ " " + v);
		}

		private static String uri(URL u) {
			StringBuilder r = new StringBuilder();
			r.append(u.getProtocol());
			r.append("://");
			r.append(u.getHost());
			if(0 < u.getPort()) {
				if(u.getPort() == 80 && "http".equals(u.getProtocol())) {
				} else if(u.getPort() == 443 && "https".equals(u.getProtocol())) {
				} else {
					r.append(':').append(u.getPort());
				}
			}
			r.append(u.getPath());
			if(u.getQuery() != null)
				r.append('?').append(u.getQuery());
			return r.toString();
		}

		private static String H(String data) {
			MessageDigest md = newMD5();
			md.update(data.getBytes(UTF_8));
			return LHEX(md.digest());
		}

		private static String KD(String secret, String data) {
			MessageDigest md = newMD5();
			md.update(secret.getBytes(UTF_8));
			md.update((byte) ':');
			md.update(data.getBytes(UTF_8));
			return LHEX(md.digest());
		}

		private static MessageDigest newMD5() {
			try {
				return MessageDigest.getInstance("MD5");
			} catch(NoSuchAlgorithmException e) {
				throw new RuntimeException("No MD5 available", e);
			}
		}

		private static final char[] LHEX = {'0', '1', '2', '3', '4', '5', '6',
				'7', '8', '9',
				'a', 'b', 'c', 'd', 'e', 'f'};

		private static String LHEX(byte[] bin) {
			StringBuilder r = new StringBuilder(bin.length * 2);
			for(byte b : bin) {
				r.append(LHEX[(b >>> 4) & 0x0f]);
				r.append(LHEX[b & 0x0f]);
			}
			return r.toString();
		}

		private static Map<String, String> parse(String auth) {
			Map<String, String> p = new HashMap<>();
			int next = 0;
			while(next < auth.length()) {
				if(auth.charAt(next) == ',') {
					next++;
				}
				while(next < auth.length()
						&& Character.isWhitespace(auth.charAt(next))) {
					next++;
				}

				int eq = auth.indexOf('=', next);
				if(eq < 0 || eq + 1 == auth.length()) {
					return Collections.emptyMap();
				}

				final String name = auth.substring(next, eq);
				final String value;
				if(auth.charAt(eq + 1) == '"') {
					int dq = auth.indexOf('"', eq + 2);
					if(dq < 0) {
						return Collections.emptyMap();
					}
					value = auth.substring(eq + 2, dq);
					next = dq + 1;

				} else {
					int space = auth.indexOf(' ', eq + 1);
					int comma = auth.indexOf(',', eq + 1);
					if(space < 0)
						space = auth.length();
					if(comma < 0)
						comma = auth.length();

					final int e = Math.min(space, comma);
					value = auth.substring(eq + 1, e);
					next = e + 1;
				}
				p.put(name, value);
			}
			return p;
		}
	}

	private static class Negotiate extends HttpAuthMethod {
		private static final GSSManagerFactory GSS_MANAGER_FACTORY = GSSManagerFactory
				.detect();

		private static final Oid OID;

		static {
			try {
				OID = new Oid("1.3.6.1.5.5.2");
			} catch(GSSException e) {
				throw new Error("Cannot create NEGOTIATE oid.", e);
			}
		}

		private final byte[] prevToken;

		public Negotiate(String hdr) {
			super(Type.NEGOTIATE);
			prevToken = Base64.decode(hdr);
		}

		@Override
		void authorize(String user, String pass) {
		}

		@Override
		void configureRequest(HttpConnection conn) throws IOException {
			GSSManager gssManager = GSS_MANAGER_FACTORY.newInstance(conn
					.getURL());
			String host = conn.getURL().getHost();
			String peerName = "HTTP@" + host.toLowerCase(Locale.ROOT);
			try {
				GSSName gssName = gssManager.createName(peerName,
						GSSName.NT_HOSTBASED_SERVICE);
				GSSContext context = gssManager.createContext(gssName, OID,
						null, GSSContext.DEFAULT_LIFETIME);
				context.requestCredDeleg(true);

				byte[] token = context.initSecContext(prevToken, 0,
						prevToken.length);

				conn.setRequestProperty(HDR_AUTHORIZATION, getType().getSchemeName()
						+ " " + Base64.encodeBytes(token));
			} catch(GSSException e) {
				throw new IOException(e);
			}
		}
	}
}
