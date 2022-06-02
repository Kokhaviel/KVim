/*
 * Copyright (C) 2008, 2010 Google Inc.
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org>
 * Copyright (C) 2013, Matthias Sohn <matthias.sohn@sap.com>
 * Copyright (C) 2017, 2020 Thomas Wolf <thomas.wolf@paranor.ch> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.jgit.lib.Constants.HEAD;
import static org.eclipse.jgit.lib.Constants.INFO_ALTERNATES;
import static org.eclipse.jgit.lib.Constants.INFO_HTTP_ALTERNATES;
import static org.eclipse.jgit.util.HttpSupport.ENCODING_GZIP;
import static org.eclipse.jgit.util.HttpSupport.ENCODING_X_GZIP;
import static org.eclipse.jgit.util.HttpSupport.HDR_ACCEPT;
import static org.eclipse.jgit.util.HttpSupport.HDR_ACCEPT_ENCODING;
import static org.eclipse.jgit.util.HttpSupport.HDR_CONTENT_ENCODING;
import static org.eclipse.jgit.util.HttpSupport.HDR_CONTENT_TYPE;
import static org.eclipse.jgit.util.HttpSupport.HDR_COOKIE;
import static org.eclipse.jgit.util.HttpSupport.HDR_LOCATION;
import static org.eclipse.jgit.util.HttpSupport.HDR_PRAGMA;
import static org.eclipse.jgit.util.HttpSupport.HDR_SET_COOKIE;
import static org.eclipse.jgit.util.HttpSupport.HDR_SET_COOKIE2;
import static org.eclipse.jgit.util.HttpSupport.HDR_USER_AGENT;
import static org.eclipse.jgit.util.HttpSupport.HDR_WWW_AUTHENTICATE;
import static org.eclipse.jgit.util.HttpSupport.METHOD_GET;
import static org.eclipse.jgit.util.HttpSupport.METHOD_POST;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpCookie;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.security.GeneralSecurityException;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.net.ssl.SSLHandshakeException;

import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.errors.NoRemoteRepositoryException;
import org.eclipse.jgit.errors.NotSupportedException;
import org.eclipse.jgit.errors.PackProtocolException;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.internal.storage.file.RefDirectory;
import org.eclipse.jgit.internal.transport.http.NetscapeCookieFile;
import org.eclipse.jgit.internal.transport.http.NetscapeCookieFileCache;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectIdRef;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.lib.SymbolicRef;
import org.eclipse.jgit.transport.HttpAuthMethod.Type;
import org.eclipse.jgit.transport.HttpConfig.HttpRedirectMode;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.HttpConnectionFactory;
import org.eclipse.jgit.transport.http.HttpConnectionFactory2;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.HttpSupport;
import org.eclipse.jgit.util.IO;
import org.eclipse.jgit.util.RawParseUtils;
import org.eclipse.jgit.util.StringUtils;
import org.eclipse.jgit.util.SystemReader;
import org.eclipse.jgit.util.TemporaryBuffer;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.eclipse.jgit.util.io.UnionInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TransportHttp extends HttpTransport implements WalkTransport,
		PackTransport {

	private static final Logger LOG = LoggerFactory.getLogger(TransportHttp.class);

	private static final String SVC_UPLOAD_PACK = "git-upload-pack";
	private static final String SVC_RECEIVE_PACK = "git-receive-pack";
	private static final byte[] VERSION = "version"
			.getBytes(StandardCharsets.US_ASCII);

	public enum AcceptEncoding {
		UNSPECIFIED,
		GZIP
	}

	static final TransportProtocol PROTO_HTTP = new TransportProtocol() {
		private final String[] schemeNames = {"http", "https"};

		private final Set<String> schemeSet = Collections
				.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(schemeNames)));

		@Override
		public Set<String> getSchemes() {
			return schemeSet;
		}

		@Override
		public Set<URIishField> getRequiredFields() {
			return Collections.unmodifiableSet(EnumSet.of(URIishField.HOST,
					URIishField.PATH));
		}

		@Override
		public Set<URIishField> getOptionalFields() {
			return Collections.unmodifiableSet(EnumSet.of(URIishField.USER,
					URIishField.PASS, URIishField.PORT));
		}

		@Override
		public Transport open(URIish uri, Repository local, String remoteName)
				throws NotSupportedException {
			return new TransportHttp(local, uri);
		}

		@Override
		public Transport open(URIish uri) throws NotSupportedException {
			return new TransportHttp(uri);
		}
	};

	static final TransportProtocol PROTO_FTP = new TransportProtocol() {

		@Override
		public Set<String> getSchemes() {
			return Collections.singleton("ftp");
		}

		@Override
		public Set<URIishField> getRequiredFields() {
			return Collections.unmodifiableSet(EnumSet.of(URIishField.HOST,
					URIishField.PATH));
		}

		@Override
		public Set<URIishField> getOptionalFields() {
			return Collections.unmodifiableSet(EnumSet.of(URIishField.USER,
					URIishField.PASS, URIishField.PORT));
		}

		@Override
		public Transport open(URIish uri, Repository local, String remoteName)
				throws NotSupportedException {
			return new TransportHttp(local, uri);
		}
	};

	private URIish currentUri;
	private URL baseUrl;
	private URL objectsUrl;
	private final HttpConfig http;
	private final ProxySelector proxySelector;
	private final boolean useSmartHttp = true;
	private HttpAuthMethod authMethod = HttpAuthMethod.Type.NONE.method(null);
	private Map<String, String> headers;
	private boolean sslVerify;
	private boolean sslFailure = false;
	private final HttpConnectionFactory factory;
	private HttpConnectionFactory2.GitSession gitSession;
	private final NetscapeCookieFile cookieFile;
	private final Set<HttpCookie> relevantCookies;

	TransportHttp(Repository local, URIish uri)
			throws NotSupportedException {
		super(local, uri);
		setURI(uri);
		http = new HttpConfig(local.getConfig(), uri);
		proxySelector = ProxySelector.getDefault();
		sslVerify = http.isSslVerify();
		cookieFile = getCookieFileFromConfig(http);
		relevantCookies = filterCookies(cookieFile, baseUrl);
		factory = HttpTransport.getConnectionFactory();
	}

	private URL toURL(URIish urish) throws MalformedURLException {
		String uriString = urish.toString();
		if(!uriString.endsWith("/")) {
			uriString += '/';
		}
		return new URL(uriString);
	}

	protected void setURI(URIish uri) throws NotSupportedException {
		try {
			currentUri = uri;
			baseUrl = toURL(uri);
			objectsUrl = new URL(baseUrl, "objects/");
		} catch(MalformedURLException e) {
			throw new NotSupportedException(MessageFormat.format(JGitText.get().invalidURL, uri), e);
		}
	}

	TransportHttp(URIish uri) throws NotSupportedException {
		super(uri);
		setURI(uri);
		http = new HttpConfig(uri);
		proxySelector = ProxySelector.getDefault();
		sslVerify = http.isSslVerify();
		cookieFile = getCookieFileFromConfig(http);
		relevantCookies = filterCookies(cookieFile, baseUrl);
		factory = HttpTransport.getConnectionFactory();
	}

	private FetchConnection getConnection(HttpConnection c, InputStream in,
										  Collection<RefSpec> refSpecs,
										  String... additionalPatterns) throws IOException {
		BaseConnection f;
		if(isSmartHttp(c, "git-upload-pack")) {
			InputStream withMark = in.markSupported() ? in
					: new BufferedInputStream(in);
			readSmartHeaders(withMark, "git-upload-pack");
			f = new SmartHttpFetchConnection(withMark, refSpecs,
					additionalPatterns);
		} else {
			f = newDumbConnection(in);
		}
		f.setPeerUserAgent(c.getHeaderField(HttpSupport.HDR_SERVER));
		return (FetchConnection) f;
	}

	@Override
	public FetchConnection openFetch() throws TransportException,
			NotSupportedException {
		return openFetch(Collections.emptyList());
	}

	@Override
	public FetchConnection openFetch(Collection<RefSpec> refSpecs,
									 String... additionalPatterns)
			throws NotSupportedException, TransportException {
		try {
			TransferConfig.ProtocolVersion gitProtocol = protocol;
			if(gitProtocol == null) {
				gitProtocol = TransferConfig.ProtocolVersion.V2;
			}
			HttpConnection c = connect(SVC_UPLOAD_PACK, gitProtocol);
			try(InputStream in = openInputStream(c)) {
				return getConnection(c, in, refSpecs,
						additionalPatterns);
			}
		} catch(NotSupportedException | TransportException err) {
			throw err;
		} catch(IOException err) {
			throw new TransportException(uri, JGitText.get().errorReadingInfoRefs, err);
		}
	}

	private WalkFetchConnection newDumbConnection(InputStream in)
			throws IOException {
		HttpObjectDB d = new HttpObjectDB(objectsUrl);
		Map<String, Ref> refs;
		try(BufferedReader br = toBufferedReader(in)) {
			refs = d.readAdvertisedImpl(br);
		}

		if(!refs.containsKey(HEAD)) {
			HttpConnection conn = httpOpen(
					METHOD_GET,
					new URL(baseUrl, HEAD),
					AcceptEncoding.GZIP);
			int status = HttpSupport.response(conn);
			switch(status) {
				case HttpConnection.HTTP_OK: {
					try(BufferedReader br = toBufferedReader(
							openInputStream(conn))) {
						String line = br.readLine();
						if(line != null && line.startsWith(RefDirectory.SYMREF)) {
							String target = line.substring(RefDirectory.SYMREF.length());
							Ref r = refs.get(target);
							if(r == null)
								r = new ObjectIdRef.Unpeeled(Ref.Storage.NEW, target, null);
							r = new SymbolicRef(HEAD, r);
							refs.put(r.getName(), r);
						} else if(ObjectId.isId(line)) {
							Ref r = new ObjectIdRef.Unpeeled(Ref.Storage.NETWORK,
									HEAD, ObjectId.fromString(line));
							refs.put(r.getName(), r);
						}
					}
					break;
				}

				case HttpConnection.HTTP_NOT_FOUND:
					break;

				default:
					throw new TransportException(uri, MessageFormat.format(
							JGitText.get().cannotReadHEAD, status,
							conn.getResponseMessage()));
			}
		}

		WalkFetchConnection wfc = new WalkFetchConnection(this, d);
		wfc.available(refs);
		return wfc;
	}

	private BufferedReader toBufferedReader(InputStream in) {
		return new BufferedReader(new InputStreamReader(in, UTF_8));
	}

	@Override
	public PushConnection openPush() throws NotSupportedException,
			TransportException {
		try {
			final HttpConnection c = connect();
			try(InputStream in = openInputStream(c)) {
				if(isSmartHttp(c, SVC_RECEIVE_PACK)) {
					return smartPush(c, in);
				} else if(!useSmartHttp) {
					final String msg = JGitText.get().smartHTTPPushDisabled;
					throw new NotSupportedException(msg);

				} else {
					final String msg = JGitText.get().remoteDoesNotSupportSmartHTTPPush;
					throw new NotSupportedException(msg);
				}
			}
		} catch(NotSupportedException | TransportException err) {
			throw err;
		} catch(IOException err) {
			throw new TransportException(uri, JGitText.get().errorReadingInfoRefs, err);
		}
	}

	private PushConnection smartPush(HttpConnection c,
									 InputStream in) throws IOException {
		BufferedInputStream inBuf = new BufferedInputStream(in);
		readSmartHeaders(inBuf, "git-receive-pack");
		SmartHttpPushConnection p = new SmartHttpPushConnection(inBuf);
		p.setPeerUserAgent(c.getHeaderField(HttpSupport.HDR_SERVER));
		return p;
	}

	@Override
	public void close() {
		if(gitSession != null) {
			gitSession.close();
			gitSession = null;
		}
	}

	private NoRemoteRepositoryException createNotFoundException(URIish u,
																URL url, String msg) {
		String text;
		if(msg != null && !msg.isEmpty()) {
			text = MessageFormat.format(JGitText.get().uriNotFoundWithMessage,
					url, msg);
		} else {
			text = MessageFormat.format(JGitText.get().uriNotFound, url);
		}
		return new NoRemoteRepositoryException(u, text);
	}

	private HttpAuthMethod authFromUri(URIish u) {
		String user = u.getUser();
		String pass = u.getPass();
		if(user != null && pass != null) {
			try {
				user = URLDecoder.decode(user.replace("+", "%2B"),
						StandardCharsets.UTF_8.name());
				pass = URLDecoder.decode(pass.replace("+", "%2B"),
						StandardCharsets.UTF_8.name());
				HttpAuthMethod basic = HttpAuthMethod.Type.BASIC.method(null);
				basic.authorize(user, pass);
				return basic;
			} catch(IllegalArgumentException
					| UnsupportedEncodingException e) {
				LOG.warn(JGitText.get().httpUserInfoDecodeError, u);
			}
		}
		return HttpAuthMethod.Type.NONE.method(null);
	}

	private HttpConnection connect()
			throws TransportException, NotSupportedException {
		return connect("git-receive-pack", null);
	}

	private HttpConnection connect(String service,
								   TransferConfig.ProtocolVersion protocolVersion)
			throws TransportException, NotSupportedException {
		URL u = getServiceURL(service);
		if(HttpAuthMethod.Type.NONE.equals(authMethod.getType())) {
			authMethod = authFromUri(currentUri);
		}
		int authAttempts = 1;
		int redirects = 0;
		Collection<Type> ignoreTypes = null;
		for(; ; ) {
			try {
				final HttpConnection conn = httpOpen(METHOD_GET, u, AcceptEncoding.GZIP);
				if(useSmartHttp) {
					String exp = "application/x-" + service + "-advertisement";
					conn.setRequestProperty(HDR_ACCEPT, exp + ", */*");
				} else {
					conn.setRequestProperty(HDR_ACCEPT, "*/*");
				}
				if(TransferConfig.ProtocolVersion.V2.equals(protocolVersion)) {
					conn.setRequestProperty(
							GitProtocolConstants.PROTOCOL_HEADER,
							GitProtocolConstants.VERSION_2_REQUEST);
				}
				final int status = HttpSupport.response(conn);
				processResponseCookies(conn);
				switch(status) {
					case HttpConnection.HTTP_OK:
						if(authMethod.getType() == HttpAuthMethod.Type.NONE
								&& conn.getHeaderField(HDR_WWW_AUTHENTICATE) != null)
							authMethod = HttpAuthMethod.scanResponse(conn, ignoreTypes);
						return conn;

					case HttpConnection.HTTP_NOT_FOUND:
						throw createNotFoundException(uri, u,
								conn.getResponseMessage());

					case HttpConnection.HTTP_UNAUTHORIZED:
						authMethod = HttpAuthMethod.scanResponse(conn, ignoreTypes);
						if(authMethod.getType() == HttpAuthMethod.Type.NONE)
							throw new TransportException(uri, MessageFormat.format(
									JGitText.get().authenticationNotSupported, uri));
						CredentialsProvider credentialsProvider = getCredentialsProvider();
						if(credentialsProvider == null)
							throw new TransportException(uri,
									JGitText.get().noCredentialsProvider);
						if(3 < authAttempts
								|| !authMethod.authorize(currentUri,
								credentialsProvider)) {
							throw new TransportException(uri,
									JGitText.get().notAuthorized);
						}
						authAttempts++;
						continue;

					case HttpConnection.HTTP_FORBIDDEN:
						throw new TransportException(uri, MessageFormat.format(
								JGitText.get().serviceNotPermitted, baseUrl,
								service));

					case HttpConnection.HTTP_MOVED_PERM:
					case HttpConnection.HTTP_MOVED_TEMP:
					case HttpConnection.HTTP_SEE_OTHER:
					case HttpConnection.HTTP_11_MOVED_PERM:
					case HttpConnection.HTTP_11_MOVED_TEMP:
						if(http.getFollowRedirects() == HttpRedirectMode.FALSE) {
							throw new TransportException(uri,
									MessageFormat.format(
											JGitText.get().redirectsOff,
											status));
						}
						URIish newUri = redirect(u,
								conn.getHeaderField(HDR_LOCATION),
								Constants.INFO_REFS, redirects++);
						setURI(newUri);
						u = getServiceURL(service);
						authAttempts = 1;
						break;
					default:
						String err = status + " " + conn.getResponseMessage();
						throw new TransportException(uri, err);
				}
			} catch(NotSupportedException | TransportException e) {
				throw e;
			} catch(InterruptedIOException e) {
				throw new TransportException(uri, MessageFormat.format(
						JGitText.get().connectionTimeOut, u.getHost()), e);
			} catch(SocketException e) {
				throw new TransportException(uri,
						JGitText.get().connectionFailed, e);
			} catch(SSLHandshakeException e) {
				handleSslFailure(e);
			} catch(IOException e) {
				if(authMethod.getType() != HttpAuthMethod.Type.NONE) {
					if(ignoreTypes == null) {
						ignoreTypes = new HashSet<>();
					}
					ignoreTypes.add(authMethod.getType());

					authMethod = HttpAuthMethod.Type.NONE.method(null);
					authAttempts = 1;

					continue;
				}

				throw new TransportException(uri, MessageFormat.format(JGitText.get().cannotOpenService, service), e);
			}
		}
	}

	void processResponseCookies(HttpConnection conn) {
		if(cookieFile != null && http.getSaveCookies()) {
			List<HttpCookie> foundCookies = new LinkedList<>();

			List<String> cookieHeaderValues = conn
					.getHeaderFields(HDR_SET_COOKIE);
			if(!cookieHeaderValues.isEmpty()) {
				foundCookies.addAll(
						extractCookies(HDR_SET_COOKIE, cookieHeaderValues));
			}
			cookieHeaderValues = conn.getHeaderFields(HDR_SET_COOKIE2);
			if(!cookieHeaderValues.isEmpty()) {
				foundCookies.addAll(
						extractCookies(HDR_SET_COOKIE2, cookieHeaderValues));
			}
			if(!foundCookies.isEmpty()) {
				try {
					Set<HttpCookie> cookies = cookieFile.getCookies(false);
					cookies.addAll(foundCookies);
					cookieFile.write(baseUrl);
					relevantCookies.addAll(foundCookies);
				} catch(IOException | IllegalArgumentException
						| InterruptedException e) {
					LOG.warn(MessageFormat.format(
							JGitText.get().couldNotPersistCookies,
							cookieFile.getPath()), e);
				}
			}
		}
	}

	private List<HttpCookie> extractCookies(String headerKey,
											List<String> headerValues) {
		List<HttpCookie> foundCookies = new LinkedList<>();
		for(String headerValue : headerValues) {
			foundCookies
					.addAll(HttpCookie.parse(headerKey + ':' + headerValue));
		}
		for(HttpCookie foundCookie : foundCookies) {
			String domain = foundCookie.getDomain();
			if(domain != null && domain.startsWith(".")) {
				foundCookie.setDomain(domain.substring(1));
			}
		}
		return foundCookies;
	}

	private static class CredentialItems {
		CredentialItem.InformationalMessage message;
		CredentialItem.YesNoType now;
		CredentialItem.YesNoType forRepo;
		CredentialItem.YesNoType always;

		public CredentialItem[] items() {
			if(forRepo == null) {
				return new CredentialItem[] {message, now, always};
			}
			return new CredentialItem[] {message, now, forRepo, always};
		}
	}

	private void handleSslFailure(Throwable e) throws TransportException {
		if(sslFailure || !trustInsecureSslConnection(e.getCause())) {
			throw new TransportException(uri,
					MessageFormat.format(
							JGitText.get().sslFailureExceptionMessage,
							currentUri.setPass(null)),
					e);
		}
		sslFailure = true;
	}

	private boolean trustInsecureSslConnection(Throwable cause) {
		if(cause instanceof CertificateException
				|| cause instanceof CertPathBuilderException
				|| cause instanceof CertPathValidatorException) {
			CredentialsProvider provider = getCredentialsProvider();
			if(provider != null) {
				CredentialItems trust = constructSslTrustItems(cause);
				CredentialItem[] items = trust.items();
				if(provider.supports(items)) {
					boolean answered = provider.get(uri, items);
					if(answered) {
						boolean trustNow = trust.now.getValue();
						boolean trustLocal = trust.forRepo != null
								&& trust.forRepo.getValue();
						boolean trustAlways = trust.always.getValue();
						if(trustNow || trustLocal || trustAlways) {
							sslVerify = false;
							if(trustAlways) {
								updateSslVerifyUser();
							} else if(trustLocal) {
								updateSslVerify(local.getConfig());
							}
							return true;
						}
					}
				}
			}
		}
		return false;
	}

	private CredentialItems constructSslTrustItems(Throwable cause) {
		CredentialItems items = new CredentialItems();
		String info = MessageFormat.format(JGitText.get().sslFailureInfo,
				currentUri.setPass(null));
		String sslMessage = cause.getLocalizedMessage();
		if(sslMessage == null) {
			sslMessage = cause.toString();
		}
		sslMessage = MessageFormat.format(JGitText.get().sslFailureCause,
				sslMessage);
		items.message = new CredentialItem.InformationalMessage(info + '\n'
				+ sslMessage + '\n'
				+ JGitText.get().sslFailureTrustExplanation);
		items.now = new CredentialItem.YesNoType(JGitText.get().sslTrustNow);
		if(local != null) {
			items.forRepo = new CredentialItem.YesNoType(
					MessageFormat.format(JGitText.get().sslTrustForRepo,
							local.getDirectory()));
		}
		items.always = new CredentialItem.YesNoType(
				JGitText.get().sslTrustAlways);
		return items;
	}

	private void updateSslVerify(StoredConfig config) {
		String uriPattern = uri.getScheme() + "://" + uri.getHost();
		int port = uri.getPort();
		if(port > 0) {
			uriPattern += ":" + port;
		}
		config.setBoolean(HttpConfig.HTTP, uriPattern,
				HttpConfig.SSL_VERIFY_KEY, false);
		try {
			config.save();
		} catch(IOException e) {
			LOG.error(JGitText.get().sslVerifyCannotSave, e);
		}
	}

	private void updateSslVerifyUser() {
		StoredConfig userConfig;
		try {
			userConfig = SystemReader.getInstance().getUserConfig();
			updateSslVerify(userConfig);
		} catch(IOException | ConfigInvalidException e) {
			LOG.error(e.getMessage(), e);
		}
	}

	private URIish redirect(URL currentUrl, String location, String checkFor,
							int redirects)
			throws TransportException {
		if(location == null || location.isEmpty()) {
			throw new TransportException(uri,
					MessageFormat.format(JGitText.get().redirectLocationMissing,
							baseUrl));
		}
		if(redirects >= http.getMaxRedirects()) {
			throw new TransportException(uri,
					MessageFormat.format(JGitText.get().redirectLimitExceeded,
							http.getMaxRedirects(), baseUrl,
							location));
		}
		try {
			URI redirectTo = new URI(location);
			boolean resetAuth = !StringUtils
					.isEmptyOrNull(redirectTo.getUserInfo());
			String currentHost = currentUrl.getHost();
			redirectTo = currentUrl.toURI().resolve(redirectTo);
			resetAuth = resetAuth || !currentHost.equals(redirectTo.getHost());
			String redirected = redirectTo.toASCIIString();
			if(!isValidRedirect(baseUrl, redirected, checkFor)) {
				throw new TransportException(uri,
						MessageFormat.format(JGitText.get().redirectBlocked,
								baseUrl, redirected));
			}
			redirected = redirected.substring(0, redirected.indexOf(checkFor));
			URIish result = new URIish(redirected);
			if(resetAuth) {
				authMethod = HttpAuthMethod.Type.NONE.method(null);
			}
			if(LOG.isInfoEnabled()) {
				LOG.info(MessageFormat.format(JGitText.get().redirectHttp,
						uri.setPass(null),
						redirects, baseUrl, result));
			}
			return result;
		} catch(URISyntaxException e) {
			throw new TransportException(uri,
					MessageFormat.format(JGitText.get().invalidRedirectLocation,
							baseUrl, location),
					e);
		}
	}

	private boolean isValidRedirect(URL current, String next, String checkFor) {
		String oldProtocol = current.getProtocol().toLowerCase(Locale.ROOT);
		int schemeEnd = next.indexOf("://");
		if(schemeEnd < 0) {
			return false;
		}
		String newProtocol = next.substring(0, schemeEnd)
				.toLowerCase(Locale.ROOT);
		if(!oldProtocol.equals(newProtocol)) {
			if(!"https".equals(newProtocol)) {
				return false;
			}
		}
		return next.contains(checkFor);
	}

	private URL getServiceURL(String service)
			throws NotSupportedException {
		try {
			final StringBuilder b = new StringBuilder();
			b.append(baseUrl);

			if(b.charAt(b.length() - 1) != '/') {
				b.append('/');
			}
			b.append(Constants.INFO_REFS);

			if(useSmartHttp) {
				b.append(b.indexOf("?") < 0 ? '?' : '&');
				b.append("service=");
				b.append(service);
			}

			return new URL(b.toString());
		} catch(MalformedURLException e) {
			throw new NotSupportedException(MessageFormat.format(JGitText.get().invalidURL, uri), e);
		}
	}

	protected HttpConnection httpOpen(String method, URL u,
									  AcceptEncoding acceptEncoding) throws IOException {
		if(method == null || u == null || acceptEncoding == null) {
			throw new NullPointerException();
		}

		final Proxy proxy = HttpSupport.proxyFor(proxySelector, u);
		HttpConnection conn = factory.create(u, proxy);

		if(gitSession == null && (factory instanceof HttpConnectionFactory2)) {
			gitSession = ((HttpConnectionFactory2) factory).newSession();
		}
		if(gitSession != null) {
			try {
				gitSession.configure(conn, sslVerify);
			} catch(GeneralSecurityException e) {
				throw new IOException(e.getMessage(), e);
			}
		} else if(!sslVerify && "https".equals(u.getProtocol())) {
			HttpSupport.disableSslVerify(conn);
		}

		conn.setInstanceFollowRedirects(false);

		conn.setRequestMethod(method);
		conn.setUseCaches(false);
		if(acceptEncoding == AcceptEncoding.GZIP) {
			conn.setRequestProperty(HDR_ACCEPT_ENCODING, ENCODING_GZIP);
		}
		conn.setRequestProperty(HDR_PRAGMA, "no-cache");
		if(http.getUserAgent() != null) {
			conn.setRequestProperty(HDR_USER_AGENT, http.getUserAgent());
		} else if(UserAgent.get() != null) {
			conn.setRequestProperty(HDR_USER_AGENT, UserAgent.get());
		}
		int timeOut = getTimeout();
		if(timeOut != -1) {
			int effTimeOut = timeOut * 1000;
			conn.setConnectTimeout(effTimeOut);
			conn.setReadTimeout(effTimeOut);
		}
		addHeaders(conn, http.getExtraHeaders());
		if(!relevantCookies.isEmpty()) {
			setCookieHeader(conn);
		}

		if(this.headers != null && !this.headers.isEmpty()) {
			for(Map.Entry<String, String> entry : this.headers.entrySet()) {
				conn.setRequestProperty(entry.getKey(), entry.getValue());
			}
		}
		authMethod.configureRequest(conn);
		return conn;
	}

	static void addHeaders(HttpConnection conn, List<String> headersToAdd) {
		for(String header : headersToAdd) {
			int colon = header.indexOf(':');
			String key = null;
			if(colon > 0) {
				key = header.substring(0, colon).trim();
			}
			if(key == null || key.isEmpty()) {
				LOG.warn(MessageFormat.format(
						JGitText.get().invalidHeaderFormat, header));
			} else if(HttpSupport.scanToken(key, 0) != key.length()) {
				LOG.warn(MessageFormat.format(JGitText.get().invalidHeaderKey,
						header));
			} else {
				String value = header.substring(colon + 1).trim();
				if(!StandardCharsets.US_ASCII.newEncoder().canEncode(value)) {
					LOG.warn(MessageFormat
							.format(JGitText.get().invalidHeaderValue, header));
				} else {
					conn.setRequestProperty(key, value);
				}
			}
		}
	}

	private void setCookieHeader(HttpConnection conn) {
		StringBuilder cookieHeaderValue = new StringBuilder();
		for(HttpCookie cookie : relevantCookies) {
			if(!cookie.hasExpired()) {
				if(cookieHeaderValue.length() > 0) {
					cookieHeaderValue.append(';');
				}
				cookieHeaderValue.append(cookie);
			}
		}
		if(cookieHeaderValue.length() > 0) {
			conn.setRequestProperty(HDR_COOKIE, cookieHeaderValue.toString());
		}
	}

	final InputStream openInputStream(HttpConnection conn)
			throws IOException {
		InputStream input = conn.getInputStream();
		if(isGzipContent(conn))
			input = new GZIPInputStream(input);
		return input;
	}

	IOException wrongContentType(String expType, String actType) {
		final String why = MessageFormat.format(JGitText.get().expectedReceivedContentType, expType, actType);
		return new TransportException(uri, why);
	}

	private NetscapeCookieFile getCookieFileFromConfig(
			HttpConfig config) {
		String path = config.getCookieFile();
		if(!StringUtils.isEmptyOrNull(path)) {
			try {
				FS fs = local != null ? local.getFS() : FS.DETECTED;
				File f;
				if(path.startsWith("~/")) {
					f = fs.resolve(fs.userHome(), path.substring(2));
				} else {
					f = new File(path);
					if(!f.isAbsolute()) {
						f = fs.resolve(null, path);
						LOG.warn(MessageFormat.format(
								JGitText.get().cookieFilePathRelative, f));
					}
				}
				return NetscapeCookieFileCache.getInstance(config)
						.getEntry(f.toPath());
			} catch(InvalidPathException e) {
				LOG.warn(MessageFormat.format(
						JGitText.get().couldNotReadCookieFile, path), e);
			}
		}
		return null;
	}

	private static Set<HttpCookie> filterCookies(NetscapeCookieFile cookieFile,
												 URL url) {
		if(cookieFile != null) {
			return filterCookies(cookieFile.getCookies(true), url);
		}
		return Collections.emptySet();
	}

	private static Set<HttpCookie> filterCookies(Set<HttpCookie> allCookies,
												 URL url) {
		Set<HttpCookie> filteredCookies = new HashSet<>();
		for(HttpCookie cookie : allCookies) {
			if(cookie.hasExpired()) {
				continue;
			}
			if(!matchesCookieDomain(url.getHost(), cookie.getDomain())) {
				continue;
			}
			if(!matchesCookiePath(url.getPath(), cookie.getPath())) {
				continue;
			}
			if(cookie.getSecure() && !"https".equals(url.getProtocol())) {
				continue;
			}
			filteredCookies.add(cookie);
		}
		return filteredCookies;
	}

	static boolean matchesCookieDomain(String host, String cookieDomain) {
		cookieDomain = cookieDomain.toLowerCase(Locale.ROOT);
		host = host.toLowerCase(Locale.ROOT);
		if(host.equals(cookieDomain)) {
			return true;
		}
		if(!host.endsWith(cookieDomain)) {
			return false;
		}
		return host.charAt(host.length() - cookieDomain.length() - 1) == '.';
	}

	static boolean matchesCookiePath(String path, String cookiePath) {
		if(cookiePath.equals(path)) {
			return true;
		}
		if(!cookiePath.endsWith("/")) {
			cookiePath += "/";
		}
		return path.startsWith(cookiePath);
	}

	private boolean isSmartHttp(HttpConnection c, String service) {
		final String expType = "application/x-" + service + "-advertisement";
		final String actType = c.getContentType();
		return expType.equals(actType);
	}

	private boolean isGzipContent(HttpConnection c) {
		return ENCODING_GZIP.equals(c.getHeaderField(HDR_CONTENT_ENCODING))
				|| ENCODING_X_GZIP.equals(c.getHeaderField(HDR_CONTENT_ENCODING));
	}

	private void readSmartHeaders(InputStream in, String service)
			throws IOException {
		final byte[] magic = new byte[14];
		if(!in.markSupported()) {
			throw new TransportException(uri,
					JGitText.get().inputStreamMustSupportMark);
		}
		in.mark(14);
		IO.readFully(in, magic, 0, magic.length);
		if(Arrays.equals(Arrays.copyOfRange(magic, 4, 11), VERSION)
				&& magic[12] >= '1' && magic[12] <= '9') {
			in.reset();
			return;
		}
		if(magic[4] != '#') {
			throw new TransportException(uri, MessageFormat.format(
					JGitText.get().expectedPktLineWithService, RawParseUtils.decode(magic)));
		}
		in.reset();
		final PacketLineIn pckIn = new PacketLineIn(in);
		final String exp = "# service=" + service;
		final String act = pckIn.readString();
		if(!exp.equals(act)) {
			throw new TransportException(uri, MessageFormat.format(
					JGitText.get().expectedGot, exp, act));
		}
	}

	class HttpObjectDB extends WalkRemoteObjectDatabase {
		private final URL httpObjectsUrl;

		HttpObjectDB(URL b) {
			httpObjectsUrl = b;
		}

		@Override
		URIish getURI() {
			return new URIish(httpObjectsUrl);
		}

		@Override
		Collection<WalkRemoteObjectDatabase> getAlternates() throws IOException {
			try {
				return readAlternates(INFO_HTTP_ALTERNATES);
			} catch(FileNotFoundException ignored) {
			}

			try {
				return readAlternates(INFO_ALTERNATES);
			} catch(FileNotFoundException ignored) {
			}

			return null;
		}

		@Override
		WalkRemoteObjectDatabase openAlternate(String location)
				throws IOException {
			return new HttpObjectDB(new URL(httpObjectsUrl, location));
		}

		@Override
		BufferedReader openReader(String path) throws IOException {
			InputStream is = open(path, AcceptEncoding.GZIP).in;
			return new BufferedReader(new InputStreamReader(is, UTF_8));
		}

		@Override
		Collection<String> getPackNames() throws IOException {
			final Collection<String> packs = new ArrayList<>();
			try(BufferedReader br = openReader(INFO_PACKS)) {
				for(; ; ) {
					final String s = br.readLine();
					if(s == null || s.length() == 0)
						break;
					if(!s.startsWith("P pack-") || !s.endsWith(".pack"))
						throw invalidAdvertisement(s);
					packs.add(s.substring(2));
				}
				return packs;
			} catch(FileNotFoundException err) {
				return packs;
			}
		}

		@Override
		FileStream open(String path) throws IOException {
			return open(path, AcceptEncoding.UNSPECIFIED);
		}

		FileStream open(String path, AcceptEncoding acceptEncoding)
				throws IOException {
			final URL u = new URL(httpObjectsUrl, path);
			final HttpConnection c = httpOpen(METHOD_GET, u, acceptEncoding);
			switch(HttpSupport.response(c)) {
				case HttpConnection.HTTP_OK:
					final InputStream in = openInputStream(c);
					if(!isGzipContent(c)) {
						final int len = c.getContentLength();
						return new FileStream(in, len);
					}
					return new FileStream(in);
				case HttpConnection.HTTP_NOT_FOUND:
					throw new FileNotFoundException(u.toString());
				default:
					throw new IOException(u + ": "
							+ HttpSupport.response(c) + " "
							+ c.getResponseMessage());
			}
		}

		Map<String, Ref> readAdvertisedImpl(final BufferedReader br)
				throws IOException {
			final TreeMap<String, Ref> avail = new TreeMap<>();
			for(; ; ) {
				String line = br.readLine();
				if(line == null)
					break;

				final int tab = line.indexOf('\t');
				if(tab < 0)
					throw invalidAdvertisement(line);

				String name;
				final ObjectId id;

				name = line.substring(tab + 1);
				id = ObjectId.fromString(line.substring(0, tab));
				if(name.endsWith("^{}")) {
					name = name.substring(0, name.length() - 3);
					final Ref prior = avail.get(name);
					if(prior == null)
						throw outOfOrderAdvertisement(name);

					if(prior.getPeeledObjectId() != null)
						throw duplicateAdvertisement(name + "^{}");

					avail.put(name, new ObjectIdRef.PeeledTag(
							Ref.Storage.NETWORK, name,
							prior.getObjectId(), id));
				} else {
					Ref prior = avail.put(name, new ObjectIdRef.PeeledNonTag(
							Ref.Storage.NETWORK, name, id));
					if(prior != null)
						throw duplicateAdvertisement(name);
				}
			}
			return avail;
		}

		private PackProtocolException outOfOrderAdvertisement(String n) {
			return new PackProtocolException(MessageFormat.format(JGitText.get().advertisementOfCameBefore, n, n));
		}

		private PackProtocolException invalidAdvertisement(String n) {
			return new PackProtocolException(MessageFormat.format(JGitText.get().invalidAdvertisementOf, n));
		}

		private PackProtocolException duplicateAdvertisement(String n) {
			return new PackProtocolException(MessageFormat.format(JGitText.get().duplicateAdvertisementsOf, n));
		}

		@Override
		void close() {
		}
	}

	class SmartHttpFetchConnection extends BasePackFetchConnection {
		private MultiRequestService svc;

		SmartHttpFetchConnection(InputStream advertisement,
								 Collection<RefSpec> refSpecs, String... additionalPatterns)
				throws TransportException {
			super(TransportHttp.this);
			statelessRPC = true;

			init(advertisement, DisabledOutputStream.INSTANCE);
			outNeedsEnd = false;
			if(!readAdvertisedRefs()) {
				LongPollService service = new LongPollService(SVC_UPLOAD_PACK,
						getProtocolVersion());
				init(service.getInputStream(), service.getOutputStream());
				lsRefs(refSpecs, additionalPatterns);
			}
		}

		@Override
		protected void doFetch(final ProgressMonitor monitor,
							   final Collection<Ref> want, final Set<ObjectId> have,
							   final OutputStream outputStream) throws TransportException {
			try {
				svc = new MultiRequestService(SVC_UPLOAD_PACK,
						getProtocolVersion());
				init(svc.getInputStream(), svc.getOutputStream());
				super.doFetch(monitor, want, have, outputStream);
			} finally {
				svc = null;
			}
		}

		@Override
		protected void onReceivePack() {
			svc.finalRequest = true;
		}
	}

	class SmartHttpPushConnection extends BasePackPushConnection {
		SmartHttpPushConnection(InputStream advertisement)
				throws TransportException {
			super(TransportHttp.this);
			statelessRPC = true;

			init(advertisement, DisabledOutputStream.INSTANCE);
			outNeedsEnd = false;
			readAdvertisedRefs();
		}

		@Override
		protected void doPush(final ProgressMonitor monitor,
							  final Map<String, RemoteRefUpdate> refUpdates,
							  OutputStream outputStream) throws TransportException {
			final Service svc = new MultiRequestService(SVC_RECEIVE_PACK,
					getProtocolVersion());
			init(svc.getInputStream(), svc.getOutputStream());
			super.doPush(monitor, refUpdates, outputStream);
		}
	}

	abstract class Service {
		protected final String serviceName;

		protected final String requestType;

		protected final String responseType;

		protected HttpConnection conn;

		protected HttpOutputStream out;

		protected final HttpExecuteStream execute;

		protected final TransferConfig.ProtocolVersion protocolVersion;

		final UnionInputStream in;

		Service(String serviceName,
				TransferConfig.ProtocolVersion protocolVersion) {
			this.serviceName = serviceName;
			this.protocolVersion = protocolVersion;
			this.requestType = "application/x-" + serviceName + "-request";
			this.responseType = "application/x-" + serviceName + "-result";

			this.out = new HttpOutputStream();
			this.execute = new HttpExecuteStream();
			this.in = new UnionInputStream(execute);
		}

		void openStream() throws IOException {
			conn = httpOpen(METHOD_POST, new URL(baseUrl, serviceName),
					AcceptEncoding.GZIP);
			conn.setInstanceFollowRedirects(false);
			conn.setDoOutput(true);
			conn.setRequestProperty(HDR_CONTENT_TYPE, requestType);
			conn.setRequestProperty(HDR_ACCEPT, responseType);
			if(TransferConfig.ProtocolVersion.V2.equals(protocolVersion)) {
				conn.setRequestProperty(GitProtocolConstants.PROTOCOL_HEADER,
						GitProtocolConstants.VERSION_2_REQUEST);
			}
		}

		void sendRequest() throws IOException {
			TemporaryBuffer buf = new TemporaryBuffer.Heap(
					http.getPostBuffer());
			try(GZIPOutputStream gzip = new GZIPOutputStream(buf)) {
				out.writeTo(gzip, null);
				if(out.length() < buf.length())
					buf = out;
			} catch(IOException err) {
				buf = out;
			}

			HttpAuthMethod authenticator = null;
			Collection<Type> ignoreTypes = EnumSet.noneOf(Type.class);
			int authAttempts = 1;
			int redirects = 0;
			for(; ; ) {
				try {
					openStream();
					if(buf != out) {
						conn.setRequestProperty(HDR_CONTENT_ENCODING,
								ENCODING_GZIP);
					}
					conn.setFixedLengthStreamingMode((int) buf.length());
					try(OutputStream httpOut = conn.getOutputStream()) {
						buf.writeTo(httpOut, null);
					}

					final int status = HttpSupport.response(conn);
					switch(status) {
						case HttpConnection.HTTP_NOT_FOUND:
							throw createNotFoundException(uri, conn.getURL(),
									conn.getResponseMessage());

						case HttpConnection.HTTP_FORBIDDEN:
							throw new TransportException(uri,
									MessageFormat.format(
											JGitText.get().serviceNotPermitted,
											baseUrl, serviceName));

						case HttpConnection.HTTP_MOVED_PERM:
						case HttpConnection.HTTP_MOVED_TEMP:
						case HttpConnection.HTTP_11_MOVED_PERM:
						case HttpConnection.HTTP_11_MOVED_TEMP:
							if(http.getFollowRedirects() != HttpRedirectMode.TRUE) {
								return;
							}
							currentUri = redirect(conn.getURL(),
									conn.getHeaderField(HDR_LOCATION),
									'/' + serviceName, redirects++);
							try {
								baseUrl = toURL(currentUri);
							} catch(MalformedURLException e) {
								throw new TransportException(uri,
										MessageFormat.format(
												JGitText.get().invalidRedirectLocation,
												baseUrl, currentUri),
										e);
							}
							continue;

						case HttpConnection.HTTP_UNAUTHORIZED:
							HttpAuthMethod nextMethod = HttpAuthMethod
									.scanResponse(conn, ignoreTypes);
							switch(nextMethod.getType()) {
								case NONE:
									throw new TransportException(uri,
											MessageFormat.format(
													JGitText.get().authenticationNotSupported,
													conn.getURL()));
								case NEGOTIATE:
									ignoreTypes.add(HttpAuthMethod.Type.NEGOTIATE);
									if(authenticator != null) {
										ignoreTypes.add(authenticator.getType());
									}
									authAttempts = 1;
									break;
								default:
									ignoreTypes.add(HttpAuthMethod.Type.NEGOTIATE);
									if(authenticator == null || authenticator
											.getType() != nextMethod.getType()) {
										if(authenticator != null) {
											ignoreTypes.add(authenticator.getType());
										}
										authAttempts = 1;
									}
									break;
							}
							authMethod = nextMethod;
							authenticator = nextMethod;
							CredentialsProvider credentialsProvider = getCredentialsProvider();
							if(credentialsProvider == null) {
								throw new TransportException(uri,
										JGitText.get().noCredentialsProvider);
							}
							if(3 < authAttempts || !authMethod
									.authorize(currentUri, credentialsProvider)) {
								throw new TransportException(uri,
										JGitText.get().notAuthorized);
							}
							authAttempts++;
							continue;

						default:
							return;
					}
				} catch(SSLHandshakeException e) {
					handleSslFailure(e);
				} catch(SocketException | InterruptedIOException e) {
					throw e;
				} catch(IOException e) {
					if(authenticator == null || authMethod
							.getType() != HttpAuthMethod.Type.NONE) {
						if(authMethod.getType() != HttpAuthMethod.Type.NONE) {
							ignoreTypes.add(authMethod.getType());
						}
						authMethod = HttpAuthMethod.Type.NONE.method(null);
						authenticator = authMethod;
						authAttempts = 1;
						continue;
					}
					throw e;
				}
			}
		}

		void openResponse() throws IOException {
			final int status = HttpSupport.response(conn);
			if(status != HttpConnection.HTTP_OK) {
				throw new TransportException(uri, status + " "
						+ conn.getResponseMessage());
			}

			final String contentType = conn.getContentType();
			if(!responseType.equals(contentType)) {
				conn.getInputStream().close();
				throw wrongContentType(responseType, contentType);
			}
		}

		HttpOutputStream getOutputStream() {
			return out;
		}

		InputStream getInputStream() {
			return in;
		}

		abstract void execute() throws IOException;

		class HttpExecuteStream extends InputStream {
			@Override
			public int read() throws IOException {
				execute();
				return -1;
			}

			@Override
			public int read(byte[] b, int off, int len) throws IOException {
				execute();
				return -1;
			}

			@Override
			public long skip(long n) throws IOException {
				execute();
				return 0;
			}
		}

		class HttpOutputStream extends TemporaryBuffer {
			HttpOutputStream() {
				super(http.getPostBuffer());
			}

			@Override
			protected OutputStream overflow() throws IOException {
				openStream();
				conn.setChunkedStreamingMode(0);
				return conn.getOutputStream();
			}
		}
	}

	class MultiRequestService extends Service {
		boolean finalRequest;

		MultiRequestService(String serviceName,
							TransferConfig.ProtocolVersion protocolVersion) {
			super(serviceName, protocolVersion);
		}

		@Override
		void execute() throws IOException {
			out.close();

			if(conn == null) {
				if(out.length() == 0) {
					if(finalRequest)
						return;
					throw new TransportException(uri,
							JGitText.get().startingReadStageWithoutWrittenRequestDataPendingIsNotSupported);
				}

				sendRequest();
			}

			out.reset();

			openResponse();

			in.add(openInputStream(conn));
			if(!finalRequest)
				in.add(execute);
			conn = null;
		}
	}

	class LongPollService extends Service {

		LongPollService(String serviceName,
						TransferConfig.ProtocolVersion protocolVersion) {
			super(serviceName, protocolVersion);
		}

		@Override
		void execute() throws IOException {
			out.close();
			if(conn == null)
				sendRequest();
			openResponse();
			in.add(openInputStream(conn));
		}
	}
}
