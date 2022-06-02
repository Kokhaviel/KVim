/**
 * Copyright (c) 2004-2011 QOS.ch
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

public class NOPLogger extends NamedLoggerBase {

	private static final long serialVersionUID = -517220405410904473L;
	public static final NOPLogger NOP_LOGGER = new NOPLogger();

	protected NOPLogger() {
	}

	@Override
	public String getName() {
		return "NOP";
	}

	final public boolean isDebugEnabled() {
		return false;
	}

	final public void debug(String msg) {
	}

	final public void debug(String format, Object arg) {
	}

	final public void debug(String format, Object arg1, Object arg2) {
	}

	final public void debug(String format, Object... argArray) {
	}

	final public void debug(String msg, Throwable t) {
	}

	final public boolean isInfoEnabled() {
		return false;
	}

	final public void info(String msg) {
	}

	final public boolean isWarnEnabled() {
		return false;
	}

	final public void warn(String msg) {
	}

	final public void warn(String format, Object arg1) {
	}

	final public void warn(String format, Object... argArray) {
	}

	final public void warn(String msg, Throwable t) {
	}

	final public boolean isErrorEnabled() {
		return false;
	}

	final public void error(String format, Object arg1) {
	}

	final public void error(String format, Object arg1, Object arg2) {
	}

	final public void error(String msg, Throwable t) {
	}
}
