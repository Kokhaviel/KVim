/*
 * Copyright (C) 2008, 2009 Google Inc.
 * Copyright (C) 2008, Marek Zawirski <marek.zawirski@gmail.com>
 * Copyright (C) 2008, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2008, 2022 Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.errors.NotSupportedException;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.hooks.Hooks;
import org.eclipse.jgit.hooks.PrePushHook;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.storage.pack.PackConfig;

import java.io.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.net.URL;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

public abstract class Transport implements AutoCloseable {
	public enum Operation {
		FETCH,
		PUSH
	}

	private static final List<WeakReference<TransportProtocol>> protocols =
			new CopyOnWriteArrayList<>();

	static {
		register(TransportLocal.PROTO_LOCAL);
		register(TransportBundleFile.PROTO_BUNDLE);
		register(TransportAmazonS3.PROTO_S3);
		register(TransportGitAnon.PROTO_GIT);
		register(TransportSftp.PROTO_SFTP);
		register(TransportHttp.PROTO_FTP);
		register(TransportHttp.PROTO_HTTP);
		register(TransportGitSsh.PROTO_SSH);

		registerByService();
	}

	private static void registerByService() {
		ClassLoader ldr = Thread.currentThread().getContextClassLoader();
		if(ldr == null)
			ldr = Transport.class.getClassLoader();
		Enumeration<URL> catalogs = catalogs(ldr);
		while(catalogs.hasMoreElements())
			scan(ldr, catalogs.nextElement());
	}

	private static Enumeration<URL> catalogs(ClassLoader ldr) {
		try {
			String prefix = "META-INF/services/";
			String name = prefix + Transport.class.getName();
			return ldr.getResources(name);
		} catch(IOException err) {
			return new Vector<URL>().elements();
		}
	}

	private static void scan(ClassLoader ldr, URL url) {
		try(BufferedReader br = new BufferedReader(
				new InputStreamReader(url.openStream(), UTF_8))) {
			String line;
			while((line = br.readLine()) != null) {
				line = line.trim();
				if(line.length() == 0)
					continue;
				int comment = line.indexOf('#');
				if(comment == 0)
					continue;
				if(comment != -1)
					line = line.substring(0, comment).trim();
				load(ldr, line);
			}
		} catch(IOException e) {
			// Ignore errors
		}
	}

	private static void load(ClassLoader ldr, String cn) {
		Class<?> clazz;
		try {
			clazz = Class.forName(cn, false, ldr);
		} catch(ClassNotFoundException notBuiltin) {
			return;
		}

		for(Field f : clazz.getDeclaredFields()) {
			if((f.getModifiers() & Modifier.STATIC) == Modifier.STATIC
					&& TransportProtocol.class.isAssignableFrom(f.getType())) {
				TransportProtocol proto;
				try {
					proto = (TransportProtocol) f.get(null);
				} catch(IllegalArgumentException | IllegalAccessException e) {
					continue;
				}
				if(proto != null)
					register(proto);
			}
		}
	}

	public static void register(TransportProtocol proto) {
		protocols.add(0, new WeakReference<>(proto));
	}

	public static Transport open(Repository local, String remote)
			throws NotSupportedException, URISyntaxException,
			TransportException {
		return open(local, remote, Operation.FETCH);
	}

	public static Transport open(final Repository local, final String remote,
								 final Operation op) throws NotSupportedException,
			URISyntaxException, TransportException {
		if(local != null) {
			final RemoteConfig cfg = new RemoteConfig(local.getConfig(), remote);
			if(doesNotExist(cfg)) {
				return open(local, new URIish(remote), null);
			}
			return open(local, cfg, op);
		}
		return open(new URIish(remote));

	}

	public static List<Transport> openAll(final Repository local,
										  final String remote, final Operation op)
			throws NotSupportedException, URISyntaxException,
			TransportException {
		final RemoteConfig cfg = new RemoteConfig(local.getConfig(), remote);
		if(doesNotExist(cfg)) {
			final ArrayList<Transport> transports = new ArrayList<>(1);
			transports.add(open(local, new URIish(remote), null));
			return transports;
		}
		return openAll(local, cfg, op);
	}

	public static Transport open(Repository local, RemoteConfig cfg)
			throws NotSupportedException, TransportException {
		return open(local, cfg, Operation.FETCH);
	}

	public static Transport open(final Repository local,
								 final RemoteConfig cfg, final Operation op)
			throws NotSupportedException, TransportException {
		final List<URIish> uris = getURIs(cfg, op);
		if(uris.isEmpty())
			throw new IllegalArgumentException(MessageFormat.format(
					JGitText.get().remoteConfigHasNoURIAssociated, cfg.getName()));
		final Transport tn = open(local, uris.get(0), cfg.getName());
		tn.applyConfig(cfg);
		return tn;
	}

	public static List<Transport> openAll(final Repository local,
										  final RemoteConfig cfg, final Operation op)
			throws NotSupportedException, TransportException {
		final List<URIish> uris = getURIs(cfg, op);
		final List<Transport> transports = new ArrayList<>(uris.size());
		for(URIish uri : uris) {
			final Transport tn = open(local, uri, cfg.getName());
			tn.applyConfig(cfg);
			transports.add(tn);
		}
		return transports;
	}

	private static List<URIish> getURIs(final RemoteConfig cfg,
										final Operation op) {
		switch(op) {
			case FETCH:
				return cfg.getURIs();
			case PUSH: {
				List<URIish> uris = cfg.getPushURIs();
				if(uris.isEmpty())
					uris = cfg.getURIs();
				return uris;
			}
			default:
				throw new IllegalArgumentException(op.toString());
		}
	}

	private static boolean doesNotExist(RemoteConfig cfg) {
		return cfg.getURIs().isEmpty() && cfg.getPushURIs().isEmpty();
	}

	public static Transport open(Repository local, URIish uri)
			throws NotSupportedException, TransportException {
		return open(local, uri, null);
	}

	public static Transport open(Repository local, URIish uri, String remoteName)
			throws NotSupportedException, TransportException {
		for(WeakReference<TransportProtocol> ref : protocols) {
			TransportProtocol proto = ref.get();
			if(proto == null) {
				protocols.remove(ref);
				continue;
			}

			if(proto.canHandle(uri, local, remoteName)) {
				Transport tn = proto.open(uri, local, remoteName);
				tn.prePush = Hooks.prePush(local, tn.hookOutRedirect);
				tn.prePush.setRemoteLocation(uri.toString());
				tn.prePush.setRemoteName(remoteName);
				return tn;
			}
		}

		throw new NotSupportedException(MessageFormat.format(JGitText.get().URINotSupported, uri));
	}

	public static Transport open(URIish uri) throws NotSupportedException, TransportException {
		for(WeakReference<TransportProtocol> ref : protocols) {
			TransportProtocol proto = ref.get();
			if(proto == null) {
				protocols.remove(ref);
				continue;
			}

			if(proto.canHandle(uri, null, null))
				return proto.open(uri);
		}

		throw new NotSupportedException(MessageFormat.format(JGitText.get().URINotSupported, uri));
	}

	public static Collection<RemoteRefUpdate> findRemoteRefUpdatesFor(
			final Repository db, final Collection<RefSpec> specs,
			final Map<String, RefLeaseSpec> leases,
			Collection<RefSpec> fetchSpecs) throws IOException {
		if(fetchSpecs == null)
			fetchSpecs = Collections.emptyList();
		final List<RemoteRefUpdate> result = new LinkedList<>();
		final Collection<RefSpec> procRefs = expandPushWildcardsFor(db, specs);

		for(RefSpec spec : procRefs) {
			if(spec.isMatching()) {
				result.add(new RemoteRefUpdate(spec.isForceUpdate(),
						fetchSpecs));
				continue;
			}
			String srcSpec = spec.getSource();
			final Ref srcRef = db.findRef(srcSpec);
			if(srcRef != null)
				srcSpec = srcRef.getName();

			String destSpec = spec.getDestination();
			if(destSpec == null) {
				destSpec = srcSpec;
			}

			if(srcRef != null && !destSpec.startsWith(Constants.R_REFS)) {
				final String n = srcRef.getName();
				final int kindEnd = n.indexOf('/', Constants.R_REFS.length());
				destSpec = n.substring(0, kindEnd + 1) + destSpec;
			}

			final boolean forceUpdate = spec.isForceUpdate();
			final String localName = findTrackingRefName(destSpec, fetchSpecs);
			final RefLeaseSpec leaseSpec = leases.get(destSpec);
			final ObjectId expected = leaseSpec == null ? null :
					db.resolve(leaseSpec.getExpected());
			final RemoteRefUpdate rru = new RemoteRefUpdate(db, srcSpec,
					destSpec, forceUpdate, localName, expected);
			result.add(rru);
		}
		return result;
	}

	private static Collection<RefSpec> expandPushWildcardsFor(
			final Repository db, final Collection<RefSpec> specs)
			throws IOException {
		final Collection<RefSpec> procRefs = new LinkedHashSet<>();

		List<Ref> localRefs = null;
		for(RefSpec spec : specs) {
			if(!spec.isMatching() && spec.isWildcard()) {
				if(localRefs == null) {
					localRefs = db.getRefDatabase().getRefs();
				}
				for(Ref localRef : localRefs) {
					if(spec.matchSource(localRef)) {
						procRefs.add(spec.expandFromSource(localRef));
					}
				}
			} else {
				procRefs.add(spec);
			}
		}
		return procRefs;
	}

	static String findTrackingRefName(final String remoteName,
									  final Collection<RefSpec> fetchSpecs) {
		for(RefSpec fetchSpec : fetchSpecs) {
			if(fetchSpec.matchSource(remoteName)) {
				if(fetchSpec.isWildcard()) {
					return fetchSpec.expandFromSource(remoteName)
							.getDestination();
				}
				return fetchSpec.getDestination();
			}
		}
		return null;
	}

	public static final boolean DEFAULT_FETCH_THIN = true;
	public static final boolean DEFAULT_PUSH_THIN = false;

	protected final Repository local;
	protected final URIish uri;
	private String optionUploadPack = RemoteConfig.DEFAULT_UPLOAD_PACK;
	private List<RefSpec> fetch = Collections.emptyList();
	private TagOpt tagopt = TagOpt.NO_TAGS;
	private boolean fetchThin = DEFAULT_FETCH_THIN;
	private String optionReceivePack = RemoteConfig.DEFAULT_RECEIVE_PACK;
	private List<RefSpec> push = Collections.emptyList();
	private boolean pushThin = DEFAULT_PUSH_THIN;
	private boolean pushAtomic;
	private boolean dryRun;
	private ObjectChecker objectChecker;
	private boolean removeDeletedRefs;
	private FilterSpec filterSpec = FilterSpec.NO_FILTER;
	private int timeout;
	private PackConfig packConfig;
	private CredentialsProvider credentialsProvider;
	private List<String> pushOptions;
	private PrintStream hookOutRedirect;
	private PrePushHook prePush;

	@Nullable
	TransferConfig.ProtocolVersion protocol;

	protected Transport(Repository local, URIish uri) {
		final TransferConfig tc = local.getConfig().get(TransferConfig.KEY);
		this.local = local;
		this.uri = uri;
		this.protocol = tc.protocolVersion;
		this.objectChecker = tc.newObjectChecker();
		this.credentialsProvider = CredentialsProvider.getDefault();
		prePush = Hooks.prePush(local, hookOutRedirect);
	}

	protected Transport(URIish uri) {
		this.uri = uri;
		this.local = null;
		this.objectChecker = new ObjectChecker();
		this.credentialsProvider = CredentialsProvider.getDefault();
	}

	public URIish getURI() {
		return uri;
	}

	public String getOptionUploadPack() {
		return optionUploadPack;
	}

	public void setOptionUploadPack(String where) {
		if(where != null && where.length() > 0)
			optionUploadPack = where;
		else
			optionUploadPack = RemoteConfig.DEFAULT_UPLOAD_PACK;
	}

	public TagOpt getTagOpt() {
		return tagopt;
	}

	public void setTagOpt(TagOpt option) {
		tagopt = option != null ? option : TagOpt.AUTO_FOLLOW;
	}

	public boolean isFetchThin() {
		return fetchThin;
	}

	public void setFetchThin(boolean fetchThin) {
		this.fetchThin = fetchThin;
	}

	public boolean isCheckFetchedObjects() {
		return getObjectChecker() != null;
	}

	public void setCheckFetchedObjects(boolean check) {
		if(check && objectChecker == null)
			setObjectChecker(new ObjectChecker());
		else if(!check && objectChecker != null)
			setObjectChecker(null);
	}

	public ObjectChecker getObjectChecker() {
		return objectChecker;
	}

	public void setObjectChecker(ObjectChecker impl) {
		objectChecker = impl;
	}

	public String getOptionReceivePack() {
		return optionReceivePack;
	}

	public void setOptionReceivePack(String optionReceivePack) {
		if(optionReceivePack != null && optionReceivePack.length() > 0)
			this.optionReceivePack = optionReceivePack;
		else
			this.optionReceivePack = RemoteConfig.DEFAULT_RECEIVE_PACK;
	}

	public boolean isPushThin() {
		return pushThin;
	}

	public void setPushThin(boolean pushThin) {
		this.pushThin = pushThin;
	}

	public boolean isPushAtomic() {
		return pushAtomic;
	}

	public void setPushAtomic(boolean atomic) {
		this.pushAtomic = atomic;
	}

	public boolean isRemoveDeletedRefs() {
		return removeDeletedRefs;
	}

	public void setRemoveDeletedRefs(boolean remove) {
		removeDeletedRefs = remove;
	}

	@Deprecated
	public final long getFilterBlobLimit() {
		return filterSpec.getBlobLimit();
	}

	@Deprecated
	public final void setFilterBlobLimit(long bytes) {
		setFilterSpec(FilterSpec.withBlobLimit(bytes));
	}

	public final FilterSpec getFilterSpec() {
		return filterSpec;
	}

	public final void setFilterSpec(@NonNull FilterSpec filter) {
		filterSpec = requireNonNull(filter);
	}

	public void applyConfig(RemoteConfig cfg) {
		setOptionUploadPack(cfg.getUploadPack());
		setOptionReceivePack(cfg.getReceivePack());
		setTagOpt(cfg.getTagOpt());
		fetch = cfg.getFetchRefSpecs();
		push = cfg.getPushRefSpecs();
		timeout = cfg.getTimeout();
	}

	public boolean isDryRun() {
		return dryRun;
	}

	public void setDryRun(boolean dryRun) {
		this.dryRun = dryRun;
	}

	public int getTimeout() {
		return timeout;
	}

	public void setTimeout(int seconds) {
		timeout = seconds;
	}

	public PackConfig getPackConfig() {
		if(packConfig == null)
			packConfig = new PackConfig(local);
		return packConfig;
	}

	public void setCredentialsProvider(CredentialsProvider credentialsProvider) {
		this.credentialsProvider = credentialsProvider;
	}

	public CredentialsProvider getCredentialsProvider() {
		return credentialsProvider;
	}

	public List<String> getPushOptions() {
		return pushOptions;
	}

	public void setPushOptions(List<String> pushOptions) {
		this.pushOptions = pushOptions;
	}

	public FetchResult fetch(final ProgressMonitor monitor,
							 Collection<RefSpec> toFetch)
			throws NotSupportedException, TransportException {
		return fetch(monitor, toFetch, null);
	}

	public FetchResult fetch(final ProgressMonitor monitor,
							 Collection<RefSpec> toFetch, String branch)
			throws NotSupportedException,
			TransportException {
		if(toFetch == null || toFetch.isEmpty()) {
			if(fetch.isEmpty())
				throw new TransportException(JGitText.get().nothingToFetch);
			toFetch = fetch;
		} else if(!fetch.isEmpty()) {
			final Collection<RefSpec> tmp = new ArrayList<>(toFetch);
			for(RefSpec requested : toFetch) {
				final String reqSrc = requested.getSource();
				for(RefSpec configured : fetch) {
					final String cfgSrc = configured.getSource();
					final String cfgDst = configured.getDestination();
					if(cfgSrc.equals(reqSrc) && cfgDst != null) {
						tmp.add(configured);
						break;
					}
				}
			}
			toFetch = tmp;
		}

		final FetchResult result = new FetchResult();
		new FetchProcess(this, toFetch).execute(monitor, result, branch);

		local.autoGC(monitor);

		return result;
	}

	public OperationResult push(final ProgressMonitor monitor,
								Collection<RemoteRefUpdate> toPush, OutputStream out)
			throws NotSupportedException,
			TransportException {
		if(toPush == null || toPush.isEmpty()) {
			try {
				toPush = findRemoteRefUpdatesFor(push);
			} catch(final IOException e) {
				throw new TransportException(MessageFormat.format(
						JGitText.get().problemWithResolvingPushRefSpecsLocally, e.getMessage()), e);
			}
			if(toPush.isEmpty())
				throw new TransportException(JGitText.get().nothingToPush);
		}

		final PushProcess pushProcess = new PushProcess(this, toPush, prePush,
				out);
		return pushProcess.execute(monitor);
	}

	public OperationResult push(final ProgressMonitor monitor,
								Collection<RemoteRefUpdate> toPush) throws NotSupportedException,
			TransportException {
		return push(monitor, toPush, null);
	}

	public Collection<RemoteRefUpdate> findRemoteRefUpdatesFor(
			final Collection<RefSpec> specs) throws IOException {
		return findRemoteRefUpdatesFor(local, specs, Collections.emptyMap(),
				fetch);
	}

	public Collection<RemoteRefUpdate> findRemoteRefUpdatesFor(
			final Collection<RefSpec> specs,
			final Map<String, RefLeaseSpec> leases) throws IOException {
		return findRemoteRefUpdatesFor(local, specs, leases,
				fetch);
	}

	public abstract FetchConnection openFetch() throws NotSupportedException,
			TransportException;

	public FetchConnection openFetch(Collection<RefSpec> refSpecs,
									 String... additionalPatterns)
			throws NotSupportedException, TransportException {
		return openFetch();
	}

	public abstract PushConnection openPush() throws NotSupportedException,
			TransportException;

	@Override
	public abstract void close();
}
