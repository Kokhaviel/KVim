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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLConnection;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

import org.eclipse.jgit.errors.NotSupportedException;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectIdRef;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Ref.Storage;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.SymbolicRef;

public class TransportAmazonS3 extends HttpTransport implements WalkTransport {
	static final String S3_SCHEME = "amazon-s3"; 

	static final TransportProtocol PROTO_S3 = new TransportProtocol() {

		@Override
		public Set<String> getSchemes() {
			return Collections.singleton(S3_SCHEME);
		}

		@Override
		public Set<URIishField> getRequiredFields() {
			return Collections.unmodifiableSet(EnumSet.of(URIishField.USER,
					URIishField.HOST, URIishField.PATH));
		}

		@Override
		public Set<URIishField> getOptionalFields() {
			return Collections.unmodifiableSet(EnumSet.of(URIishField.PASS));
		}

		@Override
		public Transport open(URIish uri, Repository local, String remoteName)
				throws NotSupportedException {
			return new TransportAmazonS3(local, uri);
		}
	};

	final AmazonS3 s3;

	final String bucket;

	private final String keyPrefix;

	TransportAmazonS3(final Repository local, final URIish uri)
			throws NotSupportedException {
		super(local, uri);

		Properties props = loadProperties();
		File directory = local.getDirectory();
		if (!props.containsKey("tmpdir") && directory != null) 
			props.put("tmpdir", directory.getPath()); 

		s3 = new AmazonS3(props);
		bucket = uri.getHost();

		String p = uri.getPath();
		if (p.startsWith("/")) 
			p = p.substring(1);
		if (p.endsWith("/")) 
			p = p.substring(0, p.length() - 1);
		keyPrefix = p;
	}

	private Properties loadProperties() throws NotSupportedException {
		if (local.getDirectory() != null) {
			File propsFile = new File(local.getDirectory(), uri.getUser());
			if (propsFile.isFile())
				return loadPropertiesFile(propsFile);
		}

		File propsFile = new File(local.getFS().userHome(), uri.getUser());
		if (propsFile.isFile())
			return loadPropertiesFile(propsFile);

		Properties props = new Properties();
		String user = uri.getUser();
		String pass = uri.getPass();
		if (user != null && pass != null) {
		        props.setProperty("accesskey", user); 
		        props.setProperty("secretkey", pass); 
		} else
			throw new NotSupportedException(MessageFormat.format(
					JGitText.get().cannotReadFile, propsFile));
		return props;
	}

	private static Properties loadPropertiesFile(File propsFile)
			throws NotSupportedException {
		try {
			return AmazonS3.properties(propsFile);
		} catch (IOException e) {
			throw new NotSupportedException(MessageFormat.format(
					JGitText.get().cannotReadFile, propsFile), e);
		}
	}

	@Override
	public FetchConnection openFetch() throws TransportException {
		final DatabaseS3 c = new DatabaseS3(bucket, keyPrefix + "/objects"); 
		final WalkFetchConnection r = new WalkFetchConnection(this, c);
		r.available(c.readAdvertisedRefs());
		return r;
	}

	@Override
	public PushConnection openPush() throws TransportException {
		final DatabaseS3 c = new DatabaseS3(bucket, keyPrefix + "/objects"); 
		final WalkPushConnection r = new WalkPushConnection(this, c);
		r.available(c.readAdvertisedRefs());
		return r;
	}

	@Override
	public void close() {
	}

	class DatabaseS3 extends WalkRemoteObjectDatabase {
		private final String bucketName;

		private final String objectsKey;

		DatabaseS3(final String b, final String o) {
			bucketName = b;
			objectsKey = o;
		}

		private String resolveKey(String subpath) {
			if (subpath.endsWith("/")) 
				subpath = subpath.substring(0, subpath.length() - 1);
			String k = objectsKey;
			while (subpath.startsWith(ROOT_DIR)) {
				k = k.substring(0, k.lastIndexOf('/'));
				subpath = subpath.substring(3);
			}
			return k + "/" + subpath; 
		}

		@Override
		URIish getURI() {
			URIish u = new URIish();
			u = u.setScheme(S3_SCHEME);
			u = u.setHost(bucketName);
			u = u.setPath("/" + objectsKey); 
			return u;
		}

		@Override
		Collection<WalkRemoteObjectDatabase> getAlternates() throws IOException {
			try {
				return readAlternates(Constants.INFO_ALTERNATES);
			} catch (FileNotFoundException ignored) {
			}
			return null;
		}

		@Override
		WalkRemoteObjectDatabase openAlternate(String location) {
			return new DatabaseS3(bucketName, resolveKey(location));
		}

		@Override
		Collection<String> getPackNames() throws IOException {
			final List<String> packList = s3.list(bucket, resolveKey("pack")); 
			final HashSet<String> have = new HashSet<>(packList);

			final Collection<String> packs = new ArrayList<>();
			for (String n : packList) {
				if (!n.startsWith("pack-") || !n.endsWith(".pack"))  
					continue;

				final String in = n.substring(0, n.length() - 5) + ".idx"; 
				if (have.contains(in))
					packs.add(n);
			}
			return packs;
		}

		@Override
		FileStream open(String path) throws IOException {
			final URLConnection c = s3.get(bucket, resolveKey(path));
			final InputStream raw = c.getInputStream();
			final InputStream in = s3.decrypt(c);
			final int len = c.getContentLength();
			return new FileStream(in, raw == in ? len : -1);
		}

		@Override
		void deleteFile(String path) throws IOException {
			s3.delete(bucket, resolveKey(path));
		}

		@Override
		OutputStream writeFile(final String path,
				final ProgressMonitor monitor, final String monitorTask)
				throws IOException {
			return s3.beginPut(bucket, resolveKey(path), monitor, monitorTask);
		}

		@Override
		void writeFile(String path, byte[] data) throws IOException {
			s3.put(bucket, resolveKey(path), data);
		}

		Map<String, Ref> readAdvertisedRefs() throws TransportException {
			final TreeMap<String, Ref> avail = new TreeMap<>();
			readPackedRefs(avail);
			readLooseRefs(avail);
			readRef(avail, Constants.HEAD);
			return avail;
		}

		private void readLooseRefs(TreeMap<String, Ref> avail)
				throws TransportException {
			try {
				for (final String n : s3.list(bucket, resolveKey(ROOT_DIR
						+ "refs"))) 
					readRef(avail, "refs/" + n); 
			} catch (IOException e) {
				throw new TransportException(getURI(), JGitText.get().cannotListRefs, e);
			}
		}

		private Ref readRef(TreeMap<String, Ref> avail, String rn)
				throws TransportException {
			final String s;
			String ref = ROOT_DIR + rn;
			try {
				try (BufferedReader br = openReader(ref)) {
					s = br.readLine();
				}
			} catch (FileNotFoundException noRef) {
				return null;
			} catch (IOException err) {
				throw new TransportException(getURI(), MessageFormat.format(
						JGitText.get().transportExceptionReadRef, ref), err);
			}

			if (s == null)
				throw new TransportException(getURI(), MessageFormat.format(JGitText.get().transportExceptionEmptyRef, rn));

			if (s.startsWith("ref: ")) { 
				final String target = s.substring("ref: ".length()); 
				Ref r = avail.get(target);
				if (r == null)
					r = readRef(avail, target);
				if (r == null)
					r = new ObjectIdRef.Unpeeled(Ref.Storage.NEW, target, null);
				r = new SymbolicRef(rn, r);
				avail.put(r.getName(), r);
				return r;
			}

			if (ObjectId.isId(s)) {
				final Ref r = new ObjectIdRef.Unpeeled(loose(avail.get(rn)),
						rn, ObjectId.fromString(s));
				avail.put(r.getName(), r);
				return r;
			}

			throw new TransportException(getURI(), MessageFormat.format(JGitText.get().transportExceptionBadRef, rn, s));
		}

		private Storage loose(Ref r) {
			if (r != null && r.getStorage() == Storage.PACKED)
				return Storage.LOOSE_PACKED;
			return Storage.LOOSE;
		}

		@Override
		void close() {
		}
	}
}
