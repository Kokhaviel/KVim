/*
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
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
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URL;
import java.net.URLConnection;
import java.security.DigestOutputStream;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.SortedMap;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.util.Base64;
import org.eclipse.jgit.util.HttpSupport;
import org.eclipse.jgit.util.StringUtils;
import org.eclipse.jgit.util.TemporaryBuffer;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

public class AmazonS3 {
	private static final Set<String> SIGNED_HEADERS;

	private static final String HMAC = "HmacSHA1";

	private static final String X_AMZ_ACL = "x-amz-acl";

	private static final String X_AMZ_META = "x-amz-meta-";

	static {
		SIGNED_HEADERS = new HashSet<>();
		SIGNED_HEADERS.add("content-type");
		SIGNED_HEADERS.add("content-md5");
		SIGNED_HEADERS.add("date");
	}

	private static boolean isSignedHeader(String name) {
		final String nameLC = StringUtils.toLowerCase(name);
		return SIGNED_HEADERS.contains(nameLC) || nameLC.startsWith("x-amz-");
	}

	private static String toCleanString(List<String> list) {
		final StringBuilder s = new StringBuilder();
		for(String v : list) {
			if(s.length() > 0)
				s.append(',');
			s.append(v.replaceAll("\n", "").trim());
		}
		return s.toString();
	}

	private static String remove(Map<String, String> m, String k) {
		final String r = m.remove(k);
		return r != null ? r : "";
	}

	private static String httpNow() {
		final String tz = "GMT";
		final SimpleDateFormat fmt;
		fmt = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss", Locale.US);
		fmt.setTimeZone(TimeZone.getTimeZone(tz));
		return fmt.format(new Date()) + " " + tz;
	}

	private static MessageDigest newMD5() {
		try {
			return MessageDigest.getInstance("MD5");
		} catch(NoSuchAlgorithmException e) {
			throw new RuntimeException(JGitText.get().JRELacksMD5Implementation, e);
		}
	}

	private final String publicKey;
	private final SecretKeySpec privateKey;
	private final ProxySelector proxySelector;
	private final String acl;
	final int maxAttempts;
	private final WalkEncryption encryption;
	private final File tmpDir;
	private final String domain;

	interface Keys {
		String ACCESS_KEY = "accesskey";
		String SECRET_KEY = "secretkey";
		String PASSWORD = "password";
		String CRYPTO_ALG = "crypto.algorithm";
		String CRYPTO_VER = "crypto.version";
		String ACL = "acl";
		String DOMAIN = "domain";
		String HTTP_RETRY = "httpclient.retry-max";
		String TMP_DIR = "tmpdir";
	}

	public AmazonS3(final Properties props) {
		domain = props.getProperty(Keys.DOMAIN, "s3.amazonaws.com");

		publicKey = props.getProperty(Keys.ACCESS_KEY);
		if(publicKey == null)
			throw new IllegalArgumentException(JGitText.get().missingAccesskey);

		final String secret = props.getProperty(Keys.SECRET_KEY);
		if(secret == null)
			throw new IllegalArgumentException(JGitText.get().missingSecretkey);
		privateKey = new SecretKeySpec(Constants.encodeASCII(secret), HMAC);

		final String pacl = props.getProperty(Keys.ACL, "PRIVATE");
		if(StringUtils.equalsIgnoreCase("PRIVATE", pacl))
			acl = "private";
		else if(StringUtils.equalsIgnoreCase("PUBLIC", pacl))
			acl = "public-read";
		else if(StringUtils.equalsIgnoreCase("PUBLIC-READ", pacl))
			acl = "public-read";
		else if(StringUtils.equalsIgnoreCase("PUBLIC_READ", pacl))
			acl = "public-read";
		else
			throw new IllegalArgumentException("Invalid acl: " + pacl);

		try {
			encryption = WalkEncryption.instance(props);
		} catch(GeneralSecurityException e) {
			throw new IllegalArgumentException(JGitText.get().invalidEncryption, e);
		}

		maxAttempts = Integer
				.parseInt(props.getProperty(Keys.HTTP_RETRY, "3"));
		proxySelector = ProxySelector.getDefault();

		String tmp = props.getProperty(Keys.TMP_DIR);
		tmpDir = tmp != null && tmp.length() > 0 ? new File(tmp) : null;
	}

	public URLConnection get(String bucket, String key)
			throws IOException {
		for(int curAttempt = 0; curAttempt < maxAttempts; curAttempt++) {
			final HttpURLConnection c = open("GET", bucket, key);
			authorize(c);
			switch(HttpSupport.response(c)) {
				case HttpURLConnection.HTTP_OK:
					encryption.validate(c, X_AMZ_META);
					return c;
				case HttpURLConnection.HTTP_NOT_FOUND:
					throw new FileNotFoundException(key);
				case HttpURLConnection.HTTP_INTERNAL_ERROR:
					continue;
				default:
					throw error(JGitText.get().s3ActionReading, key, c);
			}
		}
		throw maxAttempts(JGitText.get().s3ActionReading, key);
	}

	public InputStream decrypt(URLConnection u) throws IOException {
		return encryption.decrypt(u.getInputStream());
	}

	public List<String> list(String bucket, String prefix)
			throws IOException {
		if(prefix.length() > 0 && !prefix.endsWith("/"))
			prefix += "/";
		final ListParser lp = new ListParser(bucket, prefix);
		do {
			lp.list();
		} while(lp.truncated);

		Comparator<KeyInfo> comparator = Comparator.comparingLong(KeyInfo::getLastModifiedSecs);
		return lp.entries.stream().sorted(comparator.reversed())
				.map(KeyInfo::getName).collect(Collectors.toList());
	}

	public void delete(String bucket, String key)
			throws IOException {
		for(int curAttempt = 0; curAttempt < maxAttempts; curAttempt++) {
			final HttpURLConnection c = open("DELETE", bucket, key);
			authorize(c);
			switch(HttpSupport.response(c)) {
				case HttpURLConnection.HTTP_NO_CONTENT:
					return;
				case HttpURLConnection.HTTP_INTERNAL_ERROR:
					continue;
				default:
					throw error(JGitText.get().s3ActionDeletion, key, c);
			}
		}
		throw maxAttempts(JGitText.get().s3ActionDeletion, key);
	}

	public void put(String bucket, String key, byte[] data)
			throws IOException {
		if(encryption != WalkEncryption.NONE) {
			try(OutputStream os = beginPut(bucket, key, null, null)) {
				os.write(data);
			}
			return;
		}

		final String md5str = Base64.encodeBytes(newMD5().digest(data));
		final String lenstr = String.valueOf(data.length);
		for(int curAttempt = 0; curAttempt < maxAttempts; curAttempt++) {
			final HttpURLConnection c = open("PUT", bucket, key);
			c.setRequestProperty("Content-Length", lenstr);
			c.setRequestProperty("Content-MD5", md5str);
			c.setRequestProperty(X_AMZ_ACL, acl);
			authorize(c);
			c.setDoOutput(true);
			c.setFixedLengthStreamingMode(data.length);
			try(OutputStream os = c.getOutputStream()) {
				os.write(data);
			}

			switch(HttpSupport.response(c)) {
				case HttpURLConnection.HTTP_OK:
					return;
				case HttpURLConnection.HTTP_INTERNAL_ERROR:
					continue;
				default:
					throw error(JGitText.get().s3ActionWriting, key, c);
			}
		}
		throw maxAttempts(JGitText.get().s3ActionWriting, key);
	}

	public OutputStream beginPut(final String bucket, final String key,
								 final ProgressMonitor monitor, final String monitorTask)
			throws IOException {
		final MessageDigest md5 = newMD5();
		final TemporaryBuffer buffer = new TemporaryBuffer.LocalFile(tmpDir) {
			@Override
			public void close() throws IOException {
				super.close();
				try {
					putImpl(bucket, key, md5.digest(), this, monitor,
							monitorTask);
				} finally {
					destroy();
				}
			}
		};
		return encryption.encrypt(new DigestOutputStream(buffer, md5));
	}

	void putImpl(final String bucket, final String key,
				 final byte[] csum, final TemporaryBuffer buf,
				 ProgressMonitor monitor, String monitorTask) throws IOException {
		if(monitor == null)
			monitor = NullProgressMonitor.INSTANCE;
		if(monitorTask == null)
			monitorTask = MessageFormat.format(JGitText.get().progressMonUploading, key);

		final String md5str = Base64.encodeBytes(csum);
		final long len = buf.length();
		for(int curAttempt = 0; curAttempt < maxAttempts; curAttempt++) {
			final HttpURLConnection c = open("PUT", bucket, key);
			c.setFixedLengthStreamingMode(len);
			c.setRequestProperty("Content-MD5", md5str);
			c.setRequestProperty(X_AMZ_ACL, acl);
			encryption.request(c, X_AMZ_META);
			authorize(c);
			c.setDoOutput(true);
			monitor.beginTask(monitorTask, (int) (len / 1024));
			try(OutputStream os = c.getOutputStream()) {
				buf.writeTo(os, monitor);
			} finally {
				monitor.endTask();
			}

			switch(HttpSupport.response(c)) {
				case HttpURLConnection.HTTP_OK:
					return;
				case HttpURLConnection.HTTP_INTERNAL_ERROR:
					continue;
				default:
					throw error(JGitText.get().s3ActionWriting, key, c);
			}
		}
		throw maxAttempts(JGitText.get().s3ActionWriting, key);
	}

	IOException error(final String action, final String key,
					  final HttpURLConnection c) throws IOException {
		final IOException err = new IOException(MessageFormat.format(
				JGitText.get().amazonS3ActionFailed, action, key,
				HttpSupport.response(c),
				c.getResponseMessage()));
		if(c.getErrorStream() == null) {
			return err;
		}

		try(InputStream errorStream = c.getErrorStream()) {
			final ByteArrayOutputStream b = new ByteArrayOutputStream();
			byte[] buf = new byte[2048];
			for(; ; ) {
				final int n = errorStream.read(buf);
				if(n < 0) {
					break;
				}
				if(n > 0) {
					b.write(buf, 0, n);
				}
			}
			buf = b.toByteArray();
			if(buf.length > 0) {
				err.initCause(new IOException("\n" + new String(buf, UTF_8)));
			}
		}
		return err;
	}

	IOException maxAttempts(String action, String key) {
		return new IOException(MessageFormat.format(
				JGitText.get().amazonS3ActionFailedGivingUp, action, key,
				maxAttempts));
	}

	private HttpURLConnection open(final String method, final String bucket,
								   final String key) throws IOException {
		final Map<String, String> noArgs = Collections.emptyMap();
		return open(method, bucket, key, noArgs);
	}

	HttpURLConnection open(final String method, final String bucket,
						   final String key, final Map<String, String> args)
			throws IOException {
		final StringBuilder urlstr = new StringBuilder();
		urlstr.append("https://");
		urlstr.append(bucket);
		urlstr.append('.');
		urlstr.append(domain);
		urlstr.append('/');
		if(key.length() > 0)
			HttpSupport.encode(urlstr, key);
		if(!args.isEmpty()) {
			final Iterator<Map.Entry<String, String>> i;

			urlstr.append('?');
			i = args.entrySet().iterator();
			while(i.hasNext()) {
				final Map.Entry<String, String> e = i.next();
				urlstr.append(e.getKey());
				urlstr.append('=');
				HttpSupport.encode(urlstr, e.getValue());
				if(i.hasNext())
					urlstr.append('&');
			}
		}

		final URL url = new URL(urlstr.toString());
		final Proxy proxy = HttpSupport.proxyFor(proxySelector, url);
		final HttpURLConnection c;

		c = (HttpURLConnection) url.openConnection(proxy);
		c.setRequestMethod(method);
		c.setRequestProperty("User-Agent", "jgit/1.0");
		c.setRequestProperty("Date", httpNow());
		return c;
	}

	void authorize(HttpURLConnection c) throws IOException {
		final Map<String, List<String>> reqHdr = c.getRequestProperties();
		final SortedMap<String, String> sigHdr = new TreeMap<>();
		for(Map.Entry<String, List<String>> entry : reqHdr.entrySet()) {
			final String hdr = entry.getKey();
			if(isSignedHeader(hdr))
				sigHdr.put(StringUtils.toLowerCase(hdr), toCleanString(entry.getValue()));
		}

		final StringBuilder s = new StringBuilder();
		s.append(c.getRequestMethod());
		s.append('\n');

		s.append(remove(sigHdr, "content-md5"));
		s.append('\n');

		s.append(remove(sigHdr, "content-type"));
		s.append('\n');

		s.append(remove(sigHdr, "date"));
		s.append('\n');

		for(Map.Entry<String, String> e : sigHdr.entrySet()) {
			s.append(e.getKey());
			s.append(':');
			s.append(e.getValue());
			s.append('\n');
		}

		final String host = c.getURL().getHost();
		s.append('/');
		s.append(host, 0, host.length() - domain.length() - 1);
		s.append(c.getURL().getPath());

		final String sec;
		try {
			final Mac m = Mac.getInstance(HMAC);
			m.init(privateKey);
			sec = Base64.encodeBytes(m.doFinal(s.toString().getBytes(UTF_8)));
		} catch(NoSuchAlgorithmException e) {
			throw new IOException(MessageFormat.format(JGitText.get().noHMACsupport, HMAC, e.getMessage()));
		} catch(InvalidKeyException e) {
			throw new IOException(MessageFormat.format(JGitText.get().invalidKey, e.getMessage()));
		}
		c.setRequestProperty("Authorization", "AWS " + publicKey + ":" + sec);
	}

	static Properties properties(File authFile)
			throws IOException {
		final Properties p = new Properties();
		try(FileInputStream in = new FileInputStream(authFile)) {
			p.load(in);
		}
		return p;
	}

	private static final class KeyInfo {
		private final String name;
		private final long lastModifiedSecs;

		public KeyInfo(String aname, long lsecs) {
			name = aname;
			lastModifiedSecs = lsecs;
		}

		public String getName() {
			return name;
		}

		public long getLastModifiedSecs() {
			return lastModifiedSecs;
		}
	}

	private final class ListParser extends DefaultHandler {
		final List<KeyInfo> entries = new ArrayList<>();

		private final String bucket;

		private final String prefix;

		boolean truncated;

		private StringBuilder data;
		private String keyName;
		private Instant keyLastModified;

		ListParser(String bn, String p) {
			bucket = bn;
			prefix = p;
		}

		void list() throws IOException {
			final Map<String, String> args = new TreeMap<>();
			if(prefix.length() > 0)
				args.put("prefix", prefix);
			if(!entries.isEmpty())
				args.put("marker", prefix + entries.get(entries.size() - 1).getName());

			for(int curAttempt = 0; curAttempt < maxAttempts; curAttempt++) {
				final HttpURLConnection c = open("GET", bucket, "", args);
				authorize(c);
				switch(HttpSupport.response(c)) {
					case HttpURLConnection.HTTP_OK:
						truncated = false;
						data = null;
						keyName = null;
						keyLastModified = null;

						final XMLReader xr;
						try {
							xr = SAXParserFactory.newInstance().newSAXParser()
									.getXMLReader();
						} catch(SAXException | ParserConfigurationException e) {
							throw new IOException(
									JGitText.get().noXMLParserAvailable, e);
						}
						xr.setContentHandler(this);
						try(InputStream in = c.getInputStream()) {
							xr.parse(new InputSource(in));
						} catch(SAXException parsingError) {
							throw new IOException(
									MessageFormat.format(
											JGitText.get().errorListing, prefix),
									parsingError);
						}
						return;

					case HttpURLConnection.HTTP_INTERNAL_ERROR:
						continue;

					default:
						throw AmazonS3.this.error("Listing", prefix, c);
				}
			}
			throw maxAttempts("Listing", prefix);
		}

		@Override
		public void startElement(final String uri, final String name,
								 final String qName, final Attributes attributes) {
			if("Key".equals(name) || "IsTruncated".equals(name) || "LastModified".equals(name)) {
				data = new StringBuilder();
			}
			if("Contents".equals(name)) {
				keyName = null;
				keyLastModified = null;
			}
		}

		@Override
		public void ignorableWhitespace(final char[] ch, final int s,
										final int n) {
			if(data != null)
				data.append(ch, s, n);
		}

		@Override
		public void characters(char[] ch, int s, int n) {
			if(data != null)
				data.append(ch, s, n);
		}

		@Override
		public void endElement(final String uri, final String name,
							   final String qName) {
			if("Key".equals(name)) {
				keyName = data.substring(prefix.length());
			} else if("IsTruncated".equals(name)) {
				truncated = StringUtils.equalsIgnoreCase("true", data.toString());
			} else if("LastModified".equals(name)) {
				keyLastModified = Instant.parse(data.toString());
			} else if("Contents".equals(name)) {
				entries.add(new KeyInfo(keyName, keyLastModified.getEpochSecond()));
			}

			data = null;
		}
	}
}
