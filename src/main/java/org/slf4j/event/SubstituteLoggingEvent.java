package org.slf4j.event;

import org.slf4j.helpers.SubstituteLogger;

public class SubstituteLoggingEvent {

	Level level;
	String loggerName;
	SubstituteLogger logger;
	String threadName;
	String message;
	Object[] argArray;

	long timeStamp;
	Throwable throwable;

	public void setLevel(Level level) {
		this.level = level;
	}

	public void setLoggerName(String loggerName) {
		this.loggerName = loggerName;
	}

	public SubstituteLogger getLogger() {
		return logger;
	}

	public void setLogger(SubstituteLogger logger) {
		this.logger = logger;
	}

	public void setMessage(String message) {
		this.message = message;
	}

	public void setArgumentArray(Object[] argArray) {
		this.argArray = argArray;
	}

	public void setTimeStamp(long timeStamp) {
		this.timeStamp = timeStamp;
	}

	public void setThreadName(String threadName) {
		this.threadName = threadName;
	}

	public void setThrowable(Throwable throwable) {
		this.throwable = throwable;
	}

}
