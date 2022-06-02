/*
 * Copyright (C) 2007, Dave Watson <dwatson@mimvista.com>
 * Copyright (C) 2009-2010, Google Inc.
 * Copyright (C) 2007, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2006, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.internal.storage.file;

import static org.eclipse.jgit.lib.Constants.*;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.text.MessageFormat;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.util.FileUtils;

public class ReflogWriter {

	private final RefDirectory refdb;
	private final boolean forceWrite;

	public ReflogWriter(RefDirectory refdb, boolean forceWrite) {
		this.refdb = refdb;
		this.forceWrite = forceWrite;
	}

	public ReflogWriter create() throws IOException {
		FileUtils.mkdir(refdb.logsDir);
		FileUtils.mkdir(refdb.logsRefsDir);
		FileUtils.mkdir(new File(refdb.logsRefsDir, R_HEADS.substring(R_REFS.length())));
		return this;
	}

	public ReflogWriter log(String refName, ReflogEntry entry) throws IOException {
		return log(refName, entry.getOldId(), entry.getNewId(), entry.getWho(), entry.getComment());
	}

	public ReflogWriter log(String refName, ObjectId oldId, ObjectId newId, PersonIdent ident, String message) throws IOException {
		byte[] encoded = encode(oldId, newId, ident, message);
		return log(refName, encoded);
	}

	public ReflogWriter log(RefUpdate update, String msg, boolean deref) throws IOException {
		ObjectId oldId = update.getOldObjectId();
		ObjectId newId = update.getNewObjectId();
		Ref ref = update.getRef();

		PersonIdent ident = update.getRefLogIdent();
		if(ident == null) ident = new PersonIdent(refdb.getRepository());
		else ident = new PersonIdent(ident);

		byte[] rec = encode(oldId, newId, ident, msg);
		if(deref && ref.isSymbolic()) {
			log(ref.getName(), rec);
			log(ref.getLeaf().getName(), rec);
		} else log(ref.getName(), rec);
		return this;
	}

	private byte[] encode(ObjectId oldId, ObjectId newId, PersonIdent ident, String message) {
		String r = ObjectId.toString(oldId) + ' ' + ObjectId.toString(newId) + ' ' + ident.toExternalString() + '\t' +
				message.replace("\r\n", " ").replace("\n", " ") + '\n';
		return Constants.encode(r);
	}

	private FileOutputStream getFileOutputStream(File log) throws IOException {
		try {
			return new FileOutputStream(log, true);
		} catch(FileNotFoundException err) {
			File dir = log.getParentFile();
			if(dir.exists()) throw err;
			if(!dir.mkdirs() && !dir.isDirectory()) {
				throw new IOException(MessageFormat.format(JGitText.get().cannotCreateDirectory, dir));
			}
			return new FileOutputStream(log, true);
		}
	}

	private ReflogWriter log(String refName, byte[] rec) throws IOException {
		File log = refdb.logFor(refName);
		boolean write = forceWrite || shouldAutoCreateLog(refName) || log.isFile();
		if(!write) return this;

		WriteConfig wc = refdb.getRepository().getConfig().get(WriteConfig.KEY);
		try(FileOutputStream out = getFileOutputStream(log)) {
			if(wc.getFSyncRefFiles()) {
				FileChannel fc = out.getChannel();
				ByteBuffer buf = ByteBuffer.wrap(rec);
				while(0 < buf.remaining()) fc.write(buf);
				fc.force(true);
			} else out.write(rec);
		}
		return this;
	}

	private boolean shouldAutoCreateLog(String refName) {
		Repository repo = refdb.getRepository();
		CoreConfig.LogRefUpdates value = repo.isBare() ? CoreConfig.LogRefUpdates.FALSE : CoreConfig.LogRefUpdates.TRUE;
		value = repo.getConfig().getEnum(ConfigConstants.CONFIG_CORE_SECTION, null, ConfigConstants.CONFIG_KEY_LOGALLREFUPDATES, value);
		if(value != null) {
			switch(value) {
				case TRUE:
					return refName.equals(HEAD) || refName.startsWith(R_HEADS) || refName.startsWith(R_REMOTES) || refName.startsWith(R_NOTES);
				case ALWAYS:
					return refName.equals(HEAD) || refName.startsWith(R_REFS);
				default:
					break;
			}
		}
		return false;
	}
}