package org.slf4j.event;

public enum Level {

	ERROR("ERROR"), WARN("WARN"), INFO("INFO"), DEBUG("DEBUG"), TRACE("TRACE");

	private final String levelStr;

	Level(String s) {
		levelStr = s;
	}

	public String toString() {
		return levelStr;
	}

}
