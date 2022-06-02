package org.slf4j.helpers;

import org.slf4j.ILoggerFactory;
import org.slf4j.spi.SLF4JServiceProvider;

public class SubstituteServiceProvider implements SLF4JServiceProvider {
	private final SubstituteLoggerFactory loggerFactory = new SubstituteLoggerFactory();

	@Override
	public ILoggerFactory getLoggerFactory() {
		return loggerFactory;
	}

	public SubstituteLoggerFactory getSubstituteLoggerFactory() {
		return loggerFactory;
	}

	@Override
	public String getRequestedApiVersion() {
		throw new UnsupportedOperationException();
	}

	@Override
	public void initialize() {

	}
}
