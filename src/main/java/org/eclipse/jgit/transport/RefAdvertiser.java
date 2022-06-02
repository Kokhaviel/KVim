/*
 * Copyright (C) 2008, 2020 Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.jgit.lib.Constants.OBJECT_ID_STRING_LENGTH;
import static org.eclipse.jgit.transport.GitProtocolConstants.OPTION_SYMREF;
import static org.eclipse.jgit.transport.GitProtocolConstants.REF_ATTR_PEELED;
import static org.eclipse.jgit.transport.GitProtocolConstants.REF_ATTR_SYMREF_TARGET;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefComparator;
import org.eclipse.jgit.lib.Repository;

public abstract class RefAdvertiser {
	public static class PacketLineOutRefAdvertiser extends RefAdvertiser {
		private final CharsetEncoder utf8 = UTF_8.newEncoder();
		private final PacketLineOut pckOut;

		private byte[] binArr = new byte[256];
		private ByteBuffer binBuf = ByteBuffer.wrap(binArr);

		private char[] chArr = new char[256];
		private CharBuffer chBuf = CharBuffer.wrap(chArr);

		public PacketLineOutRefAdvertiser(PacketLineOut out) {
			pckOut = out;
		}

		@Override
		public void advertiseId(AnyObjectId id, String refName)
				throws IOException {
			id.copyTo(binArr, 0);
			binArr[OBJECT_ID_STRING_LENGTH] = ' ';
			binBuf.position(OBJECT_ID_STRING_LENGTH + 1);
			append(refName);
			if(first) {
				first = false;
				if(!capablities.isEmpty()) {
					append('\0');
					for(String cap : capablities) {
						append(' ');
						append(cap);
					}
				}
			}
			append('\n');
			pckOut.writePacket(binArr, 0, binBuf.position());
		}

		private void append(String str) throws CharacterCodingException {
			int n = str.length();
			if(n > chArr.length) {
				chArr = new char[n + 256];
				chBuf = CharBuffer.wrap(chArr);
			}
			str.getChars(0, n, chArr, 0);
			chBuf.position(0).limit(n);
			utf8.reset();
			for(; ; ) {
				CoderResult cr = utf8.encode(chBuf, binBuf, true);
				if(cr.isOverflow()) {
					grow();
				} else if(cr.isUnderflow()) {
					break;
				} else {
					cr.throwException();
				}
			}
		}

		private void append(int b) {
			if(!binBuf.hasRemaining()) {
				grow();
			}
			binBuf.put((byte) b);
		}

		private void grow() {
			int cnt = binBuf.position();
			byte[] tmp = new byte[binArr.length << 1];
			System.arraycopy(binArr, 0, tmp, 0, cnt);
			binArr = tmp;
			binBuf = ByteBuffer.wrap(binArr);
			binBuf.position(cnt);
		}

		@Override
		protected void writeOne(CharSequence line) throws IOException {
			pckOut.writeString(line.toString());
		}

		@Override
		protected void end() throws IOException {
			pckOut.end();
		}
	}

	private final StringBuilder tmpLine = new StringBuilder(100);
	private final char[] tmpId = new char[Constants.OBJECT_ID_STRING_LENGTH];
	final Set<String> capablities = new LinkedHashSet<>();
	private final Set<ObjectId> sent = new HashSet<>();
	private Repository repository;
	private boolean derefTags;
	boolean first = true;
	private boolean useProtocolV2;
	private final Map<String, String> symrefs = new HashMap<>();

	public void init(Repository src) {
		repository = src;
	}

	public void setUseProtocolV2(boolean b) {
		useProtocolV2 = b;
	}

	public void setDerefTags(boolean deref) {
		derefTags = deref;
	}

	public void advertiseCapability(String name) {
		capablities.add(name);
	}

	public void advertiseCapability(String name, String value) {
		if(value != null) {
			capablities.add(name + '=' + value);
		}
	}

	public void addSymref(String from, String to) {
		if(useProtocolV2) {
			symrefs.put(from, to);
		} else {
			advertiseCapability(OPTION_SYMREF, from + ':' + to);
		}
	}

	@Deprecated
	public Set<ObjectId> send(Map<String, Ref> refs) throws IOException {
		return send(refs.values());
	}

	public Set<ObjectId> send(Collection<Ref> refs) throws IOException {
		for(Ref ref : RefComparator.sort(refs)) {
			ObjectId objectId = ref.getObjectId();
			if(objectId == null) {
				continue;
			}

			if(useProtocolV2) {
				String symrefPart = symrefs.containsKey(ref.getName())
						? (' ' + REF_ATTR_SYMREF_TARGET
						+ symrefs.get(ref.getName()))
						: "";
				String peelPart = "";
				if(derefTags) {
					if(!ref.isPeeled() && repository != null) {
						ref = repository.getRefDatabase().peel(ref);
					}
					ObjectId peeledObjectId = ref.getPeeledObjectId();
					if(peeledObjectId != null) {
						peelPart = ' ' + REF_ATTR_PEELED
								+ peeledObjectId.getName();
					}
				}
				writeOne(objectId.getName() + " " + ref.getName() + symrefPart
						+ peelPart + "\n");
				continue;
			}

			advertiseAny(objectId, ref.getName());

			if(!derefTags)
				continue;

			if(!ref.isPeeled()) {
				if(repository == null)
					continue;
				ref = repository.getRefDatabase().peel(ref);
			}

			if(ref.getPeeledObjectId() != null)
				advertiseAny(ref.getPeeledObjectId(), ref.getName() + "^{}");
		}
		return sent;
	}

	public void advertiseHave(AnyObjectId id) throws IOException {
		advertiseAnyOnce(id);
	}

	public boolean isEmpty() {
		return first;
	}

	private void advertiseAnyOnce(AnyObjectId obj)
			throws IOException {
		if(!sent.contains(obj))
			advertiseAny(obj, ".have");
	}

	private void advertiseAny(AnyObjectId obj, String refName)
			throws IOException {
		sent.add(obj.toObjectId());
		advertiseId(obj, refName);
	}

	public void advertiseId(AnyObjectId id, String refName)
			throws IOException {
		tmpLine.setLength(0);
		id.copyTo(tmpId, tmpLine);
		tmpLine.append(' ');
		tmpLine.append(refName);
		if(first) {
			first = false;
			if(!capablities.isEmpty()) {
				tmpLine.append('\0');
				for(String capName : capablities) {
					tmpLine.append(' ');
					tmpLine.append(capName);
				}
				tmpLine.append(' ');
			}
		}
		tmpLine.append('\n');
		writeOne(tmpLine);
	}

	protected abstract void writeOne(CharSequence line) throws IOException;

	protected abstract void end() throws IOException;
}
