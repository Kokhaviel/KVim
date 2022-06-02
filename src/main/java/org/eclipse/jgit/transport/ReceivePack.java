/*
 * Copyright (C) 2008-2010, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import org.eclipse.jgit.errors.InvalidObjectIdException;
import org.eclipse.jgit.errors.LargeObjectException;
import org.eclipse.jgit.errors.PackProtocolException;
import org.eclipse.jgit.errors.UnpackException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.internal.submodule.SubmoduleValidator;
import org.eclipse.jgit.internal.submodule.SubmoduleValidator.SubmoduleValidationException;
import org.eclipse.jgit.internal.transport.connectivity.FullConnectivityChecker;
import org.eclipse.jgit.internal.transport.parser.FirstCommand;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ConnectivityChecker.ConnectivityCheckInfo;
import org.eclipse.jgit.transport.PacketLineIn.InputOverLimitIOException;
import org.eclipse.jgit.transport.ReceiveCommand.Result;
import org.eclipse.jgit.transport.RefAdvertiser.PacketLineOutRefAdvertiser;
import org.eclipse.jgit.util.io.InterruptTimer;
import org.eclipse.jgit.util.io.TimeoutInputStream;
import org.eclipse.jgit.util.io.TimeoutOutputStream;

import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.jgit.lib.Constants.HEAD;
import static org.eclipse.jgit.transport.GitProtocolConstants.*;
import static org.eclipse.jgit.transport.SideBandOutputStream.*;

public class ReceivePack {

	@Deprecated
	public static class FirstLine {
		private final FirstCommand command;

		public FirstLine(String line) {
			command = FirstCommand.fromLine(line);
		}

		public String getLine() {
			return command.getLine();
		}

	}

	private final Repository db;
	private final RevWalk walk;

	private final boolean biDirectionalPipe = true;
	private boolean expectDataAfterPackFooter;
	private final ObjectChecker objectChecker;
	private final boolean allowCreates;
	private final boolean allowAnyDeletes;

	private final boolean allowBranchDeletes;
	private final boolean allowNonFastForwards;
	private final boolean allowPushOptions;

	private boolean atomic;

	private final boolean allowOfsDelta;

	private final boolean allowQuiet = true;
	private PersonIdent refLogIdent;
	private final AdvertiseRefsHook advertiseRefsHook;
	private final RefFilter refFilter;
	private int timeout;
	private InterruptTimer timer;

	private TimeoutInputStream timeoutIn;
	private OutputStream origOut;
	private InputStream rawIn;
	private OutputStream rawOut;
	private OutputStream msgOut;

	private SideBandOutputStream errOut;
	private PacketLineIn pckIn;
	private PacketLineOut pckOut;

	private final MessageOutputWrapper msgOutWrapper = new MessageOutputWrapper();

	private PackParser parser;
	private Map<String, Ref> refs;
	private final Set<ObjectId> advertisedHaves;
	private Set<String> enabledCapabilities;
	private final Set<ObjectId> clientShallowCommits;
	private List<ReceiveCommand> commands;
	private final long maxCommandBytes;
	private final long maxDiscardBytes;
	private StringBuilder advertiseError;
	private boolean sideBand;
	private boolean quiet;
	private PackLock packLock;
	private boolean checkReferencedAreReachable;
	private long maxObjectSizeLimit;
	private PushCertificateParser pushCertificateParser;
	private final SignedPushConfig signedPushConfig;
	protected ConnectivityChecker connectivityChecker = new FullConnectivityChecker();
	private final PreReceiveHook preReceive;

	private final ReceiveCommandErrorHandler receiveCommandErrorHandler = new ReceiveCommandErrorHandler() {
	};

	private final UnpackErrorHandler unpackErrorHandler = new DefaultUnpackErrorHandler();
	private final PostReceiveHook postReceive;
	private boolean reportStatus;
	private boolean usePushOptions;

	public ReceivePack(Repository into) {
		db = into;
		walk = new RevWalk(db);
		walk.setRetainBody(false);

		TransferConfig tc = db.getConfig().get(TransferConfig.KEY);
		objectChecker = tc.newReceiveObjectChecker();

		ReceiveConfig rc = db.getConfig().get(ReceiveConfig::new);
		allowCreates = rc.allowCreates;
		allowAnyDeletes = true;
		allowBranchDeletes = rc.allowDeletes;
		allowNonFastForwards = rc.allowNonFastForwards;
		allowOfsDelta = rc.allowOfsDelta;
		allowPushOptions = rc.allowPushOptions;
		maxCommandBytes = rc.maxCommandBytes;
		maxDiscardBytes = rc.maxDiscardBytes;
		advertiseRefsHook = AdvertiseRefsHook.DEFAULT;
		refFilter = RefFilter.DEFAULT;
		advertisedHaves = new HashSet<>();
		clientShallowCommits = new HashSet<>();
		signedPushConfig = rc.signedPush;
		preReceive = PreReceiveHook.NULL;
		postReceive = PostReceiveHook.NULL;
	}

	private static class ReceiveConfig {
		final boolean allowCreates;
		final boolean allowDeletes;
		final boolean allowNonFastForwards;
		final boolean allowOfsDelta;
		final boolean allowPushOptions;
		final long maxCommandBytes;
		final long maxDiscardBytes;
		final SignedPushConfig signedPush;

		ReceiveConfig(Config config) {
			allowCreates = true;
			allowDeletes = !config.getBoolean("receive", "denydeletes", false);
			allowNonFastForwards = !config.getBoolean("receive",
					"denynonfastforwards", false);
			allowOfsDelta = config.getBoolean("repack", "usedeltabaseoffset",
					true);
			allowPushOptions = config.getBoolean("receive", "pushoptions",
					false);
			maxCommandBytes = config.getLong("receive",
					"maxCommandBytes",
					3 << 20);
			maxDiscardBytes = config.getLong("receive",
					"maxCommandDiscardBytes",
					-1);
			signedPush = SignedPushConfig.KEY.parse(config);
		}
	}

	class MessageOutputWrapper extends OutputStream {
		@Override
		public void write(int ch) {
			if(msgOut != null) {
				try {
					msgOut.write(ch);
				} catch(IOException ignored) {
				}
			}
		}

		@Override
		public void write(byte[] b, int off, int len) {
			if(msgOut != null) {
				try {
					msgOut.write(b, off, len);
				} catch(IOException ignored) {
				}
			}
		}

		@Override
		public void write(byte[] b) {
			write(b, 0, b.length);
		}

		@Override
		public void flush() {
			if(msgOut != null) {
				try {
					msgOut.flush();
				} catch(IOException ignored) {
				}
			}
		}
	}

	public Repository getRepository() {
		return db;
	}

	public RevWalk getRevWalk() {
		return walk;
	}

	public void setAdvertisedRefs(Map<String, Ref> allRefs,
								  Set<ObjectId> additionalHaves) throws IOException {
		refs = allRefs != null ? allRefs : getAllRefs();
		refs = refFilter.filter(refs);
		advertisedHaves.clear();

		Ref head = refs.get(HEAD);
		if(head != null && head.isSymbolic()) {
			refs.remove(HEAD);
		}

		for(Ref ref : refs.values()) {
			if(ref.getObjectId() != null) {
				advertisedHaves.add(ref.getObjectId());
			}
		}
		if(additionalHaves != null) {
			advertisedHaves.addAll(additionalHaves);
		} else {
			advertisedHaves.addAll(db.getAdditionalHaves());
		}
	}

	public boolean isCheckReferencedObjectsAreReachable() {
		return checkReferencedAreReachable;
	}

	public boolean isBiDirectionalPipe() {
		return biDirectionalPipe;
	}

	public boolean isExpectDataAfterPackFooter() {
		return expectDataAfterPackFooter;
	}

	public boolean isCheckReceivedObjects() {
		return objectChecker != null;
	}

	public boolean isAllowCreates() {
		return allowCreates;
	}

	public boolean isAllowDeletes() {
		return allowAnyDeletes;
	}

	public boolean isAllowBranchDeletes() {
		return allowBranchDeletes;
	}

	public boolean isAllowNonFastForwards() {
		return allowNonFastForwards;
	}

	public boolean isAtomic() {
		return atomic;
	}

	public void setAtomic(boolean atomic) {
		this.atomic = atomic;
	}

	public PersonIdent getRefLogIdent() {
		return refLogIdent;
	}

	public int getTimeout() {
		return timeout;
	}

	public void setTimeout(int seconds) {
		timeout = seconds;
	}

	private PushCertificateParser getPushCertificateParser() {
		if(pushCertificateParser == null) {
			pushCertificateParser = new PushCertificateParser(db,
					signedPushConfig);
		}
		return pushCertificateParser;
	}

	public List<ReceiveCommand> getAllCommands() {
		return Collections.unmodifiableList(commands);
	}

	public void sendError(String what) {
		if(refs == null) {
			if(advertiseError == null)
				advertiseError = new StringBuilder();
			advertiseError.append(what).append('\n');
		} else {
			msgOutWrapper.write(Constants.encode("error: " + what + "\n"));
		}
	}

	private void fatalError(String msg) {
		if(errOut != null) {
			try {
				errOut.write(Constants.encode(msg));
				errOut.flush();
			} catch(IOException ignored) {
			}
		} else {
			sendError(msg);
		}
	}

	private Set<ObjectId> getClientShallowCommits() {
		return clientShallowCommits;
	}

	private boolean hasCommands() {
		return !commands.isEmpty();
	}

	private boolean hasError() {
		return advertiseError != null;
	}

	protected void init(final InputStream input, final OutputStream output,
						final OutputStream messages) {
		origOut = output;
		rawIn = input;
		rawOut = output;
		msgOut = messages;

		if(timeout > 0) {
			final Thread caller = Thread.currentThread();
			timer = new InterruptTimer(caller.getName() + "-Timer");
			timeoutIn = new TimeoutInputStream(rawIn, timer);
			TimeoutOutputStream o = new TimeoutOutputStream(rawOut, timer);
			timeoutIn.setTimeout(timeout * 1000);
			o.setTimeout(timeout * 1000);
			rawIn = timeoutIn;
			rawOut = o;
		}

		pckIn = new PacketLineIn(rawIn);
		pckOut = new PacketLineOut(rawOut);
		pckOut.setFlushOnEnd(false);

		enabledCapabilities = new HashSet<>();
		commands = new ArrayList<>();
	}

	private Map<String, Ref> getAdvertisedOrDefaultRefs() throws IOException {
		if(refs == null)
			setAdvertisedRefs(null, null);
		return refs;
	}

	protected void receivePackAndCheckConnectivity() throws IOException,
			LargeObjectException, SubmoduleValidationException {
		receivePack();
		if(needCheckConnectivity()) {
			checkSubmodules();
			checkConnectivity();
		}
		parser = null;
	}

	private void unlockPack() throws IOException {
		if(packLock != null) {
			packLock.unlock();
			packLock = null;
		}
	}

	public void sendAdvertisedRefs(RefAdvertiser adv)
			throws IOException {
		if(advertiseError != null) {
			adv.writeOne("ERR " + advertiseError);
			return;
		}

		try {
			advertiseRefsHook.advertiseRefs(this);
		} catch(ServiceMayNotContinueException fail) {
			if(fail.getMessage() != null) {
				adv.writeOne("ERR " + fail.getMessage());
				fail.setOutput();
			}
			throw fail;
		}

		adv.init(db);
		adv.advertiseCapability(CAPABILITY_SIDE_BAND_64K);
		adv.advertiseCapability(CAPABILITY_DELETE_REFS);
		adv.advertiseCapability(CAPABILITY_REPORT_STATUS);
		if(allowQuiet)
			adv.advertiseCapability(CAPABILITY_QUIET);
		String nonce = getPushCertificateParser().getAdvertiseNonce();
		if(nonce != null) {
			adv.advertiseCapability(nonce);
		}
		if(db.getRefDatabase().performsAtomicTransactions())
			adv.advertiseCapability(CAPABILITY_ATOMIC);
		if(allowOfsDelta)
			adv.advertiseCapability(CAPABILITY_OFS_DELTA);
		if(allowPushOptions) {
			adv.advertiseCapability(CAPABILITY_PUSH_OPTIONS);
		}
		adv.advertiseCapability(OPTION_AGENT, UserAgent.get());
		adv.send(getAdvertisedOrDefaultRefs().values());
		for(ObjectId obj : advertisedHaves)
			adv.advertiseHave(obj);
		if(adv.isEmpty())
			adv.advertiseId(ObjectId.zeroId(), "capabilities^{}");
		adv.end();
	}

	private Map<String, Ref> getAllRefs() {
		try {
			return db.getRefDatabase().getRefs().stream()
					.collect(Collectors.toMap(Ref::getName,
							Function.identity()));
		} catch(IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private void recvCommands() throws IOException {
		PacketLineIn pck = maxCommandBytes > 0
				? new PacketLineIn(rawIn, maxCommandBytes)
				: pckIn;
		PushCertificateParser certParser = getPushCertificateParser();
		boolean firstPkt = true;
		try {
			for(; ; ) {
				String line;
				try {
					line = pck.readString();
				} catch(EOFException eof) {
					if(commands.isEmpty())
						return;
					throw eof;
				}
				if(PacketLineIn.isEnd(line)) {
					break;
				}

				if(line.length() >= 48 && line.startsWith("shallow ")) {
					parseShallow(line.substring(8, 48));
					continue;
				}

				if(firstPkt) {
					firstPkt = false;
					FirstCommand firstLine = FirstCommand.fromLine(line);
					enabledCapabilities = firstLine.getCapabilities();
					line = firstLine.getLine();
					enableCapabilities();

					if(line.equals(GitProtocolConstants.OPTION_PUSH_CERT)) {
						certParser.receiveHeader(pck, !isBiDirectionalPipe());
						continue;
					}
				}

				if(line.equals(PushCertificateParser.BEGIN_SIGNATURE)) {
					certParser.receiveSignature(pck);
					continue;
				}

				ReceiveCommand cmd = parseCommand(line);
				if(cmd.getRefName().equals(Constants.HEAD)) {
					cmd.setResult(Result.REJECTED_CURRENT_BRANCH);
				} else {
					cmd.setRef(refs.get(cmd.getRefName()));
				}
				commands.add(cmd);
				if(certParser.enabled()) {
					certParser.addCommand(cmd);
				}
			}
			certParser.build();
			if(hasCommands()) {
				readPostCommands(pck);
			}
		} catch(Throwable t) {
			discardCommands();
			throw t;
		}
	}

	private void discardCommands() {
		if(sideBand) {
			long max = maxDiscardBytes;
			if(max < 0) {
				max = Math.max(3 * maxCommandBytes, 3L << 20);
			}
			try {
				new PacketLineIn(rawIn, max).discardUntilEnd();
			} catch(IOException ignored) {
			}
		}
	}

	private void parseShallow(String idStr) throws PackProtocolException {
		ObjectId id;
		try {
			id = ObjectId.fromString(idStr);
		} catch(InvalidObjectIdException e) {
			throw new PackProtocolException(e.getMessage(), e);
		}
		clientShallowCommits.add(id);
	}

	void readPostCommands(PacketLineIn in) throws IOException {
		if(usePushOptions) {
			List<String> pushOptions = new ArrayList<>(4);
			for(; ; ) {
				String option = in.readString();
				if(PacketLineIn.isEnd(option)) {
					break;
				}
				pushOptions.add(option);
			}
		}
	}

	private void enableCapabilities() {
		reportStatus = isCapabilityEnabled(CAPABILITY_REPORT_STATUS);
		usePushOptions = isCapabilityEnabled(CAPABILITY_PUSH_OPTIONS);
		sideBand = isCapabilityEnabled(CAPABILITY_SIDE_BAND_64K);
		quiet = allowQuiet && isCapabilityEnabled(CAPABILITY_QUIET);
		if(sideBand) {
			OutputStream out = rawOut;

			rawOut = new SideBandOutputStream(CH_DATA, MAX_BUF, out);
			msgOut = new SideBandOutputStream(CH_PROGRESS, MAX_BUF, out);
			errOut = new SideBandOutputStream(CH_ERROR, MAX_BUF, out);

			pckOut = new PacketLineOut(rawOut);
			pckOut.setFlushOnEnd(false);
		}
	}

	private boolean isCapabilityEnabled(String name) {
		return enabledCapabilities.contains(name);
	}

	private boolean needPack() {
		for(ReceiveCommand cmd : commands) {
			if(cmd.getType() != ReceiveCommand.Type.DELETE)
				return true;
		}
		return false;
	}

	private void receivePack() throws IOException {
		if(timeoutIn != null)
			timeoutIn.setTimeout(10 * timeout * 1000);

		ProgressMonitor receiving = NullProgressMonitor.INSTANCE;
		ProgressMonitor resolving = NullProgressMonitor.INSTANCE;
		if(sideBand && !quiet)
			resolving = new SideBandProgressMonitor(msgOut);

		try(ObjectInserter ins = db.newObjectInserter()) {
			String lockMsg = "jgit receive-pack";
			if(getRefLogIdent() != null)
				lockMsg += " from " + getRefLogIdent().toExternalString();

			parser = ins.newPackParser(packInputStream());
			parser.setAllowThin(true);
			parser.setNeedNewObjectIds(checkReferencedAreReachable);
			parser.setNeedBaseObjectIds(checkReferencedAreReachable);
			parser.setCheckEofAfterPackFooter(!biDirectionalPipe
					&& !isExpectDataAfterPackFooter());
			parser.setExpectDataAfterPackFooter(isExpectDataAfterPackFooter());
			parser.setObjectChecker(objectChecker);
			parser.setLockMessage(lockMsg);
			parser.setMaxObjectSizeLimit(maxObjectSizeLimit);
			packLock = parser.parse(receiving, resolving);
			parser.getReceivedPackStatistics();
			ins.flush();
		}

		if(timeoutIn != null)
			timeoutIn.setTimeout(timeout * 1000);
	}

	private InputStream packInputStream() {
		return rawIn;
	}

	private boolean needCheckConnectivity() {
		return isCheckReceivedObjects()
				|| isCheckReferencedObjectsAreReachable()
				|| !getClientShallowCommits().isEmpty();
	}

	private void checkSubmodules() throws IOException, LargeObjectException,
			SubmoduleValidationException {
		ObjectDatabase odb = db.getObjectDatabase();
		if(objectChecker == null) {
			return;
		}
		for(GitmoduleEntry entry : objectChecker.getGitsubmodules()) {
			AnyObjectId blobId = entry.getBlobId();
			ObjectLoader blob = odb.open(blobId, Constants.OBJ_BLOB);

			SubmoduleValidator.assertValidGitModulesFile(
					new String(blob.getBytes(), UTF_8));
		}
	}

	private void checkConnectivity() throws IOException {
		ProgressMonitor checking = NullProgressMonitor.INSTANCE;
		if(sideBand && !quiet) {
			SideBandProgressMonitor m = new SideBandProgressMonitor(msgOut);
			m.setDelayStart(750, TimeUnit.MILLISECONDS);
			checking = m;
		}

		connectivityChecker.checkConnectivity(createConnectivityCheckInfo(),
				advertisedHaves, checking);
	}

	private ConnectivityCheckInfo createConnectivityCheckInfo() {
		ConnectivityCheckInfo info = new ConnectivityCheckInfo();
		info.setCheckObjects(checkReferencedAreReachable);
		info.setCommands(getAllCommands());
		info.setRepository(db);
		info.setParser(parser);
		info.setWalk(walk);
		return info;
	}

	private void validateCommands() {
		for(ReceiveCommand cmd : commands) {
			final Ref ref = cmd.getRef();
			if(cmd.getResult() != Result.NOT_ATTEMPTED)
				continue;

			if(cmd.getType() == ReceiveCommand.Type.DELETE) {
				if(!isAllowDeletes()) {
					cmd.setResult(Result.REJECTED_NODELETE);
					continue;
				}
				if(!isAllowBranchDeletes()
						&& ref.getName().startsWith(Constants.R_HEADS)) {
					cmd.setResult(Result.REJECTED_NODELETE);
					continue;
				}
			}

			if(cmd.getType() == ReceiveCommand.Type.CREATE) {
				if(!isAllowCreates()) {
					cmd.setResult(Result.REJECTED_NOCREATE);
					continue;
				}

				if(ref != null && !isAllowNonFastForwards()) {
					cmd.setResult(Result.REJECTED_NONFASTFORWARD);
					continue;
				}

				if(ref != null) {
					cmd.setResult(Result.REJECTED_OTHER_REASON,
							JGitText.get().refAlreadyExists);
					continue;
				}
			}

			if(cmd.getType() == ReceiveCommand.Type.DELETE && ref != null) {
				ObjectId id = ref.getObjectId();
				if(id == null) {
					id = ObjectId.zeroId();
				}
				if(!ObjectId.zeroId().equals(cmd.getOldId())
						&& !id.equals(cmd.getOldId())) {
					cmd.setResult(Result.REJECTED_OTHER_REASON,
							JGitText.get().invalidOldIdSent);
					continue;
				}
			}

			if(cmd.getType() == ReceiveCommand.Type.UPDATE) {
				if(ref == null) {
					cmd.setResult(Result.REJECTED_OTHER_REASON,
							JGitText.get().noSuchRef);
					continue;
				}
				ObjectId id = ref.getObjectId();
				if(id == null) {
					cmd.setResult(Result.REJECTED_OTHER_REASON,
							JGitText.get().cannotUpdateUnbornBranch);
					continue;
				}

				if(!id.equals(cmd.getOldId())) {
					cmd.setResult(Result.REJECTED_OTHER_REASON,
							JGitText.get().invalidOldIdSent);
					continue;
				}

				RevObject oldObj, newObj;
				try {
					oldObj = walk.parseAny(cmd.getOldId());
				} catch(IOException e) {
					receiveCommandErrorHandler
							.handleOldIdValidationException(cmd);
					continue;
				}

				try {
					newObj = walk.parseAny(cmd.getNewId());
				} catch(IOException e) {
					receiveCommandErrorHandler
							.handleNewIdValidationException(cmd);
					continue;
				}

				if(oldObj instanceof RevCommit
						&& newObj instanceof RevCommit) {
					try {
						if(walk.isMergedInto((RevCommit) oldObj,
								(RevCommit) newObj)) {
							cmd.setTypeFastForwardUpdate();
						} else {
							cmd.setType();
						}
					} catch(IOException e) {
						receiveCommandErrorHandler
								.handleFastForwardCheckException(cmd, e);
					}
				} else {
					cmd.setType();
				}

				if(cmd.getType() == ReceiveCommand.Type.UPDATE_NONFASTFORWARD
						&& !isAllowNonFastForwards()) {
					cmd.setResult(Result.REJECTED_NONFASTFORWARD);
					continue;
				}
			}

			if(!cmd.getRefName().startsWith(Constants.R_REFS)
					|| !Repository.isValidRefName(cmd.getRefName())) {
				cmd.setResult(Result.REJECTED_OTHER_REASON,
						JGitText.get().funnyRefname);
			}
		}
	}

	private boolean anyRejects() {
		for(ReceiveCommand cmd : commands) {
			if(cmd.getResult() != Result.NOT_ATTEMPTED
					&& cmd.getResult() != Result.OK)
				return true;
		}
		return false;
	}

	private void failPendingCommands() {
		ReceiveCommand.abort(commands);
	}

	protected List<ReceiveCommand> filterCommands(Result want) {
		return ReceiveCommand.filter(commands, want);
	}

	protected void executeCommands() {
		List<ReceiveCommand> toApply = filterCommands(Result.NOT_ATTEMPTED);
		if(toApply.isEmpty())
			return;

		ProgressMonitor updating = NullProgressMonitor.INSTANCE;
		if(sideBand) {
			SideBandProgressMonitor pm = new SideBandProgressMonitor(msgOut);
			pm.setDelayStart(250, TimeUnit.MILLISECONDS);
			updating = pm;
		}

		BatchRefUpdate batch = db.getRefDatabase().newBatchUpdate();
		batch.setAllowNonFastForwards(isAllowNonFastForwards());
		batch.setAtomic(isAtomic());
		batch.setRefLogIdent(getRefLogIdent());
		batch.setRefLogMessage("push", true);
		batch.addCommand(toApply);
		try {
			batch.execute(walk, updating);
		} catch(IOException e) {
			receiveCommandErrorHandler.handleBatchRefUpdateException(toApply,
					e);
		}
	}

	private void sendStatusReport(Throwable unpackError) throws IOException {
		Reporter out = new Reporter() {
			@Override
			void sendString(String s) throws IOException {
				if(reportStatus) {
					pckOut.writeString(s + "\n");
				} else if(msgOut != null) {
					msgOut.write(Constants.encode(s + "\n"));
				}
			}
		};

		try {
			if(unpackError != null) {
				out.sendString("unpack error " + unpackError.getMessage());
				if(reportStatus) {
					for(ReceiveCommand cmd : commands) {
						out.sendString("ng " + cmd.getRefName()
								+ " n/a (unpacker error)");
					}
				}
				return;
			}

			if(reportStatus) {
				out.sendString("unpack ok");
			}
			for(ReceiveCommand cmd : commands) {
				if(cmd.getResult() == Result.OK) {
					if(reportStatus) {
						out.sendString("ok " + cmd.getRefName());
					}
					continue;
				}

				final StringBuilder r = new StringBuilder();
				if(reportStatus) {
					r.append("ng ").append(cmd.getRefName()).append(" ");
				} else {
					r.append(" ! [rejected] ").append(cmd.getRefName())
							.append(" (");
				}

				if(cmd.getResult() == Result.REJECTED_MISSING_OBJECT) {
					if(cmd.getMessage() == null)
						r.append("missing object(s)");
					else if(cmd.getMessage()
							.length() == Constants.OBJECT_ID_STRING_LENGTH) {
						r.append("object ");
						r.append(cmd.getMessage());
						r.append(" missing");
					} else {
						r.append(cmd.getMessage());
					}
				} else if(cmd.getMessage() != null) {
					r.append(cmd.getMessage());
				} else {
					switch(cmd.getResult()) {
						case NOT_ATTEMPTED:
							r.append("server bug; ref not processed");
							break;

						case REJECTED_NOCREATE:
							r.append("creation prohibited");
							break;

						case REJECTED_NODELETE:
							r.append("deletion prohibited");
							break;

						case REJECTED_NONFASTFORWARD:
							r.append("non-fast forward");
							break;

						case REJECTED_CURRENT_BRANCH:
							r.append("branch is currently checked out");
							break;

						case REJECTED_OTHER_REASON:
							r.append("unspecified reason");
							break;

						case LOCK_FAILURE:
							r.append("failed to lock");
							break;

						case REJECTED_MISSING_OBJECT:
						case OK:
							throw new AssertionError();
					}
				}

				if(!reportStatus) {
					r.append(")");
				}
				out.sendString(r.toString());
			}
		} finally {
			if(reportStatus) {
				pckOut.end();
			}
		}
	}

	private void close() throws IOException {
		if(sideBand) {
			((SideBandOutputStream) msgOut).flushBuffer();
			((SideBandOutputStream) rawOut).flushBuffer();

			PacketLineOut plo = new PacketLineOut(origOut);
			plo.setFlushOnEnd(false);
			plo.end();
		}

		if(biDirectionalPipe) {
			if(!sideBand && msgOut != null)
				msgOut.flush();
			rawOut.flush();
		}
	}

	private void release() throws IOException {
		walk.close();
		unlockPack();
		timeoutIn = null;
		rawIn = null;
		rawOut = null;
		msgOut = null;
		pckIn = null;
		pckOut = null;
		refs = null;
		commands = null;
		if(timer != null) {
			try {
				timer.terminate();
			} finally {
				timer = null;
			}
		}
	}

	abstract static class Reporter {
		abstract void sendString(String s) throws IOException;
	}

	@Deprecated
	public void setEchoCommandFailures() {
	}

	public void receive(final InputStream input, final OutputStream output,
						final OutputStream messages) throws IOException {
		init(input, output, messages);
		try {
			service();
		} catch(PackProtocolException e) {
			fatalError(e.getMessage());
			throw e;
		} catch(InputOverLimitIOException e) {
			String msg = JGitText.get().tooManyCommands;
			fatalError(msg);
			throw new PackProtocolException(msg, e);
		} finally {
			try {
				close();
			} finally {
				release();
			}
		}
	}

	private void service() throws IOException {
		if(isBiDirectionalPipe()) {
			sendAdvertisedRefs(new PacketLineOutRefAdvertiser(pckOut));
			pckOut.flush();
		} else
			getAdvertisedOrDefaultRefs();
		if(hasError())
			return;

		recvCommands();

		if(hasCommands()) {
			try(PostReceiveExecutor ignored = new PostReceiveExecutor()) {
				if(needPack()) {
					try {
						receivePackAndCheckConnectivity();
					} catch(IOException | RuntimeException
							| SubmoduleValidationException | Error err) {
						unlockPack();
						unpackErrorHandler.handleUnpackException(err);
						throw new UnpackException(err);
					}
				}

				try {
					setAtomic(isCapabilityEnabled(CAPABILITY_ATOMIC));

					validateCommands();
					if(atomic && anyRejects()) {
						failPendingCommands();
					}

					preReceive.onPreReceive(
							this, filterCommands(Result.NOT_ATTEMPTED));
					if(atomic && anyRejects()) {
						failPendingCommands();
					}
					executeCommands();
				} finally {
					unlockPack();
				}

				sendStatusReport(null);
			}
			autoGc();
		}
	}

	private void autoGc() {
		Repository repo = getRepository();
		if(!repo.getConfig().getBoolean(ConfigConstants.CONFIG_RECEIVE_SECTION,
				ConfigConstants.CONFIG_KEY_AUTOGC, true)) {
			return;
		}
		repo.autoGC(NullProgressMonitor.INSTANCE);
	}

	static ReceiveCommand parseCommand(String line)
			throws PackProtocolException {
		if(line == null || line.length() < 83) {
			throw new PackProtocolException(
					JGitText.get().errorInvalidProtocolWantedOldNewRef);
		}
		String oldStr = line.substring(0, 40);
		String newStr = line.substring(41, 81);
		ObjectId oldId, newId;
		try {
			oldId = ObjectId.fromString(oldStr);
			newId = ObjectId.fromString(newStr);
		} catch(InvalidObjectIdException e) {
			throw new PackProtocolException(
					JGitText.get().errorInvalidProtocolWantedOldNewRef, e);
		}
		String name = line.substring(82);
		if(!Repository.isValidRefName(name)) {
			throw new PackProtocolException(
					JGitText.get().errorInvalidProtocolWantedOldNewRef);
		}
		return new ReceiveCommand(oldId, newId, name);
	}

	private class PostReceiveExecutor implements AutoCloseable {
		@Override
		public void close() {
			postReceive.onPostReceive(ReceivePack.this,
					filterCommands(Result.OK));
		}
	}

	private class DefaultUnpackErrorHandler implements UnpackErrorHandler {
		@Override
		public void handleUnpackException(Throwable t) throws IOException {
			sendStatusReport(t);
		}
	}
}
