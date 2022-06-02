package org.slf4j.helpers;

import org.slf4j.ILoggerFactory;
import org.slf4j.spi.SLF4JServiceProvider;

public class NOP_FallbackServiceProvider implements SLF4JServiceProvider {

	public static String REQUESTED_API_VERSION = "2.0.99";

	private final ILoggerFactory loggerFactory = new NOPLoggerFactory();

	@Override
	public ILoggerFactory getLoggerFactory() {
		return loggerFactory;
	}


	@Override
	public String getRequestedApiVersion() {
		return REQUESTED_API_VERSION;
	}

	@Override
	public void initialize() {
	}
}
