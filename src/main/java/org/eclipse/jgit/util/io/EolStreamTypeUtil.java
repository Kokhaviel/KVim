/*
 * Copyright (C) 2015, 2020 Ivan Motsch <ivan.motsch@bsiag.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.util.io;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.EnumSet;

import org.eclipse.jgit.attributes.Attributes;
import org.eclipse.jgit.lib.CoreConfig;
import org.eclipse.jgit.lib.CoreConfig.EolStreamType;
import org.eclipse.jgit.treewalk.TreeWalk.OperationType;
import org.eclipse.jgit.treewalk.WorkingTreeOptions;
import org.eclipse.jgit.util.SystemReader;
import org.eclipse.jgit.util.io.AutoLFInputStream.StreamFlag;

public final class EolStreamTypeUtil {

	public static EolStreamType detectStreamType(OperationType op, WorkingTreeOptions options, Attributes attrs) {
		switch(op) {
			case CHECKIN_OP:
				return checkInStreamType(options, attrs);
			case CHECKOUT_OP:
				return checkOutStreamType(options, attrs);
			default:
				throw new IllegalArgumentException("unknown OperationType " + op);
		}
	}

	public static InputStream wrapInputStream(InputStream in, EolStreamType conversion) {
		return wrapInputStream(in, conversion, false);
	}

	public static InputStream wrapInputStream(InputStream in, EolStreamType conversion, boolean forCheckout) {
		switch(conversion) {
			case TEXT_CRLF:
				return new AutoCRLFInputStream(in, false);
			case TEXT_LF:
				return AutoLFInputStream.create(in);
			case AUTO_CRLF:
				return new AutoCRLFInputStream(in, true);
			case AUTO_LF:
				EnumSet<StreamFlag> flags = forCheckout
						? EnumSet.of(StreamFlag.DETECT_BINARY,
						StreamFlag.FOR_CHECKOUT)
						: EnumSet.of(StreamFlag.DETECT_BINARY);
				return new AutoLFInputStream(in, flags);
			default:
				return in;
		}
	}

	public static OutputStream wrapOutputStream(OutputStream out, EolStreamType conversion) {
		switch(conversion) {
			case TEXT_CRLF:
				return new AutoCRLFOutputStream(out, false);
			case AUTO_CRLF:
				return new AutoCRLFOutputStream(out, true);
			case TEXT_LF:
				return new AutoLFOutputStream(out, false);
			case AUTO_LF:
				return new AutoLFOutputStream(out, true);
			default:
				return out;
		}
	}

	private static EolStreamType checkInStreamType(WorkingTreeOptions options, Attributes attrs) {
		if(attrs.isUnset("text")) {
			return EolStreamType.DIRECT;
		}

		if(attrs.isSet("crlf")) {
			return EolStreamType.TEXT_LF;
		} else if(attrs.isUnset("crlf")) {
			return EolStreamType.DIRECT;
		} else if("input".equals(attrs.getValue("crlf"))) {
			return EolStreamType.TEXT_LF;
		}

		if("auto".equals(attrs.getValue("text"))) {
			return EolStreamType.AUTO_LF;
		}

		String eol = attrs.getValue("eol");
		if(eol != null) {
			return EolStreamType.TEXT_LF;
		}
		if(attrs.isSet("text")) {
			return EolStreamType.TEXT_LF;
		}

		switch(options.getAutoCRLF()) {
			case TRUE:
			case INPUT:
				return EolStreamType.AUTO_LF;
			case FALSE:
				return EolStreamType.DIRECT;
		}

		return EolStreamType.DIRECT;
	}

	private static EolStreamType getOutputFormat(WorkingTreeOptions options) {
		switch(options.getAutoCRLF()) {
			case TRUE:
				return EolStreamType.TEXT_CRLF;
			case INPUT:
				return EolStreamType.DIRECT;
			default:
		}
		switch(options.getEOL()) {
			case CRLF:
				return EolStreamType.TEXT_CRLF;
			case NATIVE:
				if(SystemReader.getInstance().isWindows()) {
					return EolStreamType.TEXT_CRLF;
				}
				return EolStreamType.TEXT_LF;
			case LF:
			default:
				break;
		}
		return EolStreamType.DIRECT;
	}

	private static EolStreamType checkOutStreamType(WorkingTreeOptions options,
													Attributes attrs) {
		if(attrs.isUnset("text")) {
			return EolStreamType.DIRECT;
		}

		if(attrs.isSet("crlf")) {
			return getOutputFormat(options);
		} else if(attrs.isUnset("crlf")) {
			return EolStreamType.DIRECT;
		} else if("input".equals(attrs.getValue("crlf"))) {
			return EolStreamType.DIRECT;
		}

		String eol = attrs.getValue("eol");
		if(eol != null) {
			if("crlf".equals(eol)) {
				if("auto".equals(attrs.getValue("text"))) {
					return EolStreamType.AUTO_CRLF;
				}
				return EolStreamType.TEXT_CRLF;
			} else if("lf".equals(eol)) {
				return EolStreamType.DIRECT;
			}
		}
		if(attrs.isSet("text")) {
			return getOutputFormat(options);
		}

		if("auto".equals(attrs.getValue("text"))) {
			EolStreamType basic = getOutputFormat(options);
			switch(basic) {
				case TEXT_CRLF:
					return EolStreamType.AUTO_CRLF;
				case TEXT_LF:
					return EolStreamType.AUTO_LF;
				default:
					return basic;
			}
		}

		if(options.getAutoCRLF() == CoreConfig.AutoCRLF.TRUE) {
			return EolStreamType.AUTO_CRLF;
		}

		return EolStreamType.DIRECT;
	}

}
