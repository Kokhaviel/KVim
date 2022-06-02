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

import org.slf4j.Logger;
import org.slf4j.event.EventRecordingLogger;
import org.slf4j.event.SubstituteLoggingEvent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Queue;

public class SubstituteLogger implements Logger {

	private final String name;
	private volatile Logger _delegate;
	private Boolean delegateEventAware;
	private Method logMethodCache;
	private EventRecordingLogger eventRecordingLogger;
	private final Queue<SubstituteLoggingEvent> eventQueue;

	public final boolean createdPostInitialization;

	public SubstituteLogger(String name, Queue<SubstituteLoggingEvent> eventQueue, boolean createdPostInitialization) {
		this.name = name;
		this.eventQueue = eventQueue;
		this.createdPostInitialization = createdPostInitialization;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public boolean isDebugEnabled() {
		return delegate().isDebugEnabled();
	}

	@Override
	public void debug(String msg) {
		delegate().debug(msg);
	}

	@Override
	public void debug(String format, Object arg) {
		delegate().debug(format, arg);
	}

	@Override
	public void debug(String format, Object arg1, Object arg2) {
		delegate().debug(format, arg1, arg2);
	}

	@Override
	public void debug(String format, Object... arguments) {
		delegate().debug(format, arguments);
	}

	@Override
	public void debug(String msg, Throwable t) {
		delegate().debug(msg, t);
	}

	@Override
	public boolean isInfoEnabled() {
		return delegate().isInfoEnabled();
	}


	@Override
	public void info(String msg) {
		delegate().info(msg);
	}

	@Override
	public boolean isWarnEnabled() {
		return delegate().isWarnEnabled();
	}

	@Override
	public void warn(String msg) {
		delegate().warn(msg);
	}

	@Override
	public void warn(String format, Object arg) {
		delegate().warn(format, arg);
	}

	@Override
	public void warn(String format, Object... arguments) {
		delegate().warn(format, arguments);
	}

	@Override
	public void warn(String msg, Throwable t) {
		delegate().warn(msg, t);
	}

	@Override
	public boolean isErrorEnabled() {
		return delegate().isErrorEnabled();
	}

	@Override
	public void error(String format, Object arg) {
		delegate().error(format, arg);
	}

	@Override
	public void error(String format, Object arg1, Object arg2) {
		delegate().error(format, arg1, arg2);
	}

	@Override
	public void error(String msg, Throwable t) {
		delegate().error(msg, t);
	}

	@Override
	public boolean equals(Object o) {
		if(this == o)
			return true;
		if(o == null || getClass() != o.getClass())
			return false;

		SubstituteLogger that = (SubstituteLogger) o;

		return name.equals(that.name);
	}

	@Override
	public int hashCode() {
		return name.hashCode();
	}

	public Logger delegate() {
		if(_delegate != null) {
			return _delegate;
		}
		if(createdPostInitialization) {
			return NOPLogger.NOP_LOGGER;
		} else {
			return getEventRecordingLogger();
		}
	}

	private Logger getEventRecordingLogger() {
		if(eventRecordingLogger == null) {
			eventRecordingLogger = new EventRecordingLogger(this, eventQueue);
		}
		return eventRecordingLogger;
	}

	public void setDelegate(Logger delegate) {
		this._delegate = delegate;
	}

	public boolean isDelegateEventAware() {
		if(delegateEventAware != null)
			return delegateEventAware;

		try {
			logMethodCache = _delegate.getClass().getMethod("log", SubstituteLoggingEvent.class);
			delegateEventAware = Boolean.TRUE;
		} catch(NoSuchMethodException e) {
			delegateEventAware = Boolean.FALSE;
		}
		return delegateEventAware;
	}

	public void log(SubstituteLoggingEvent event) {
		if(isDelegateEventAware()) {
			try {
				logMethodCache.invoke(_delegate, event);
			} catch(IllegalAccessException | IllegalArgumentException | InvocationTargetException ignored) {
			}
		}
	}

	public boolean isDelegateNull() {
		return _delegate == null;
	}
}
