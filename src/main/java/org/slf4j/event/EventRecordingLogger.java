package org.slf4j.event;

import org.slf4j.helpers.AbstractLogger;
import org.slf4j.helpers.SubstituteLogger;

import java.util.Queue;

public class EventRecordingLogger extends AbstractLogger {

	private static final long serialVersionUID = -176083308134819629L;

	String name;
	SubstituteLogger logger;
	Queue<SubstituteLoggingEvent> eventQueue;

	final static boolean RECORD_ALL_EVENTS = true;

	public EventRecordingLogger(SubstituteLogger logger, Queue<SubstituteLoggingEvent> eventQueue) {
		this.logger = logger;
		this.name = logger.getName();
		this.eventQueue = eventQueue;
	}

	public String getName() {
		return name;
	}

	public boolean isDebugEnabled() {
		return RECORD_ALL_EVENTS;
	}

	public boolean isInfoEnabled() {
		return RECORD_ALL_EVENTS;
	}

	public boolean isWarnEnabled() {
		return RECORD_ALL_EVENTS;
	}

	public boolean isErrorEnabled() {
		return RECORD_ALL_EVENTS;
	}

	protected void handleNormalizedLoggingCall(Level level, String msg, Object[] args, Throwable throwable) {
		SubstituteLoggingEvent loggingEvent = new SubstituteLoggingEvent();
		loggingEvent.setTimeStamp(System.currentTimeMillis());
		loggingEvent.setLevel(level);
		loggingEvent.setLogger(logger);
		loggingEvent.setLoggerName(name);
		loggingEvent.setMessage(msg);
		loggingEvent.setThreadName(Thread.currentThread().getName());

		loggingEvent.setArgumentArray(args);
		loggingEvent.setThrowable(throwable);

		eventQueue.add(loggingEvent);

	}

}
