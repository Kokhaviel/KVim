/**
 * Copyright (c) 2004-2019 QOS.ch
 * All rights reserved.
 * <p>
 * Permission is hereby granted, free  of charge, to any person obtaining
 * a  copy  of this  software  and  associated  documentation files  (the
 * "Software"), to  deal in  the Software without  restriction, including
 * without limitation  the rights to  use, copy, modify,  merge, publish,
 * distribute,  sublicense, and/or sell  copies of  the Software,  and to
 * permit persons to whom the Software  is furnished to do so, subject to
 * the following conditions:
 * <p>
 * The  above  copyright  notice  and  this permission  notice  shall  be
 * included in all copies or substantial portions of the Software.
 * <p>
 * THE  SOFTWARE IS  PROVIDED  "AS  IS", WITHOUT  WARRANTY  OF ANY  KIND,
 * EXPRESS OR  IMPLIED, INCLUDING  BUT NOT LIMITED  TO THE  WARRANTIES OF
 * MERCHANTABILITY,    FITNESS    FOR    A   PARTICULAR    PURPOSE    AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE,  ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.slf4j.helpers;

import org.slf4j.Logger;
import org.slf4j.event.Level;

import java.io.Serializable;

public abstract class AbstractLogger implements Logger, Serializable {

	private static final long serialVersionUID = -2529255052481744503L;

	protected String name;

	public String getName() {
		return name;
	}


	public void debug(String msg) {
		if(isDebugEnabled()) {
			handle_0ArgsCall(Level.DEBUG, msg, null);
		}
	}

	public void debug(String format, Object arg) {
		if(isDebugEnabled()) {
			handle_1ArgsCall(Level.DEBUG, format, arg);
		}
	}

	public void debug(String format, Object arg1, Object arg2) {
		if(isDebugEnabled()) {
			handle2ArgsCall(Level.DEBUG, format, arg1, arg2);
		}
	}

	public void debug(String format, Object... arguments) {
		if(isDebugEnabled()) {
			handleArgArrayCall(Level.DEBUG, format, arguments);
		}
	}

	public void debug(String msg, Throwable t) {
		if(isDebugEnabled()) {
			handle_0ArgsCall(Level.DEBUG, msg, t);
		}
	}

	public void info(String msg) {
		if(isInfoEnabled()) {
			handle_0ArgsCall(Level.INFO, msg, null);
		}
	}

	public void warn(String msg) {
		if(isWarnEnabled()) {
			handle_0ArgsCall(Level.WARN, msg, null);
		}
	}

	public void warn(String format, Object arg) {
		if(isWarnEnabled()) {
			handle_1ArgsCall(Level.WARN, format, arg);
		}
	}

	public void warn(String format, Object... arguments) {
		if(isWarnEnabled()) {
			handleArgArrayCall(Level.WARN, format, arguments);
		}
	}

	public void warn(String msg, Throwable t) {
		if(isWarnEnabled()) {
			handle_0ArgsCall(Level.WARN, msg, t);
		}
	}

	public void error(String format, Object arg) {
		if(isErrorEnabled()) {
			handle_1ArgsCall(Level.ERROR, format, arg);
		}
	}

	public void error(String format, Object arg1, Object arg2) {
		if(isErrorEnabled()) {
			handle2ArgsCall(Level.ERROR, format, arg1, arg2);
		}
	}

	public void error(String msg, Throwable t) {
		if(isErrorEnabled()) {
			handle_0ArgsCall(Level.ERROR, msg, t);
		}
	}

	private void handle_0ArgsCall(Level level, String msg, Throwable t) {
		handleNormalizedLoggingCall(level, msg, null, t);
	}

	private void handle_1ArgsCall(Level level, String msg, Object arg1) {
		handleNormalizedLoggingCall(level, msg, new Object[] {arg1}, null);
	}

	private void handle2ArgsCall(Level level, String msg, Object arg1, Object arg2) {
		if(arg2 instanceof Throwable) {
			handleNormalizedLoggingCall(level, msg, new Object[] {arg1}, (Throwable) arg2);
		} else {
			handleNormalizedLoggingCall(level, msg, new Object[] {arg1, arg2}, null);
		}
	}

	private void handleArgArrayCall(Level level, String msg, Object[] args) {
		Throwable throwableCandidate = MessageFormatter.getThrowableCandidate(args);
		if(throwableCandidate != null) {
			Object[] trimmedCopy = MessageFormatter.trimmedCopy(args);
			handleNormalizedLoggingCall(level, msg, trimmedCopy, throwableCandidate);
		} else {
			handleNormalizedLoggingCall(level, msg, args, null);
		}
	}

	abstract protected void handleNormalizedLoggingCall(Level level, String msg, Object[] arguments, Throwable throwable);

}
