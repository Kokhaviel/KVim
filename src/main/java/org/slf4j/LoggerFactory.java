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
package org.slf4j;

import org.slf4j.event.SubstituteLoggingEvent;
import org.slf4j.helpers.NOP_FallbackServiceProvider;
import org.slf4j.helpers.SubstituteLogger;
import org.slf4j.helpers.SubstituteServiceProvider;
import org.slf4j.helpers.Util;
import org.slf4j.spi.SLF4JServiceProvider;

import java.io.IOException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;

public final class LoggerFactory {

	static final String CODES_PREFIX = "https://www.slf4j.org/codes.html";

	static final String NO_PROVIDERS_URL = CODES_PREFIX + "#noProviders";
	static final String IGNORED_BINDINGS_URL = CODES_PREFIX + "#ignoredBindings";

	static final String MULTIPLE_BINDINGS_URL = CODES_PREFIX + "#multiple_bindings";
	static final String VERSION_MISMATCH = CODES_PREFIX + "#version_mismatch";
	static final String SUBSTITUTE_LOGGER_URL = CODES_PREFIX + "#substituteLogger";
	static final String LOGGER_NAME_MISMATCH_URL = CODES_PREFIX + "#loggerNameMismatch";
	static final String REPLAY_URL = CODES_PREFIX + "#replay";

	static final String UNSUCCESSFUL_INIT_URL = CODES_PREFIX + "#unsuccessfulInit";
	static final String UNSUCCESSFUL_INIT_MSG = "org.slf4j.LoggerFactory in failed state. Original exception was thrown EARLIER. See also " + UNSUCCESSFUL_INIT_URL;

	static final int UNINITIALIZED = 0;
	static final int ONGOING_INITIALIZATION = 1;
	static final int FAILED_INITIALIZATION = 2;
	static final int SUCCESSFUL_INITIALIZATION = 3;
	static final int NOP_FALLBACK_INITIALIZATION = 4;

	static volatile int INITIALIZATION_STATE = UNINITIALIZED;
	static final SubstituteServiceProvider SUBST_PROVIDER = new SubstituteServiceProvider();
	static final NOP_FallbackServiceProvider NOP_FALLBACK_SERVICE_PROVIDER = new NOP_FallbackServiceProvider();

	static final String DETECT_LOGGER_NAME_MISMATCH_PROPERTY = "slf4j.detectLoggerNameMismatch";

	static boolean DETECT_LOGGER_NAME_MISMATCH = Util.safeGetBooleanSystemProperty(DETECT_LOGGER_NAME_MISMATCH_PROPERTY);

	static volatile SLF4JServiceProvider PROVIDER;

	private static List<SLF4JServiceProvider> findServiceProviders() {
		ServiceLoader<SLF4JServiceProvider> serviceLoader = ServiceLoader.load(SLF4JServiceProvider.class);
		List<SLF4JServiceProvider> providerList = new ArrayList<>();
		for(SLF4JServiceProvider provider : serviceLoader) {
			providerList.add(provider);
		}
		return providerList;
	}

	static private final String[] API_COMPATIBILITY_LIST = new String[] {"2.0"};

	private LoggerFactory() {
	}

	private static void performInitialization() {
		bind();
		if(INITIALIZATION_STATE == SUCCESSFUL_INITIALIZATION) {
			versionSanityCheck();
		}
	}

	private static void bind() {
		try {
			List<SLF4JServiceProvider> providersList = findServiceProviders();
			reportMultipleBindingAmbiguity(providersList);
			if(!providersList.isEmpty()) {
				PROVIDER = providersList.get(0);
				PROVIDER.initialize();
				INITIALIZATION_STATE = SUCCESSFUL_INITIALIZATION;
				reportActualBinding(providersList);
			} else {
				INITIALIZATION_STATE = NOP_FALLBACK_INITIALIZATION;
				Util.report("No SLF4J providers were found.");
				Util.report("Defaulting to no-operation (NOP) logger implementation");
				Util.report("See " + NO_PROVIDERS_URL + " for further details.");

				Set<URL> staticLoggerBinderPathSet = findPossibleStaticLoggerBinderPathSet();
				reportIgnoredStaticLoggerBinders(staticLoggerBinderPathSet);
			}
			postBindCleanUp();
		} catch(Exception e) {
			failedBinding(e);
			throw new IllegalStateException("Unexpected initialization failure", e);
		}
	}

	private static void reportIgnoredStaticLoggerBinders(Set<URL> staticLoggerBinderPathSet) {
		if(staticLoggerBinderPathSet.isEmpty()) {
			return;
		}
		Util.report("Class path contains SLF4J bindings targeting slf4j-api versions prior to 1.8.");
		for(URL path : staticLoggerBinderPathSet) {
			Util.report("Ignoring binding found at [" + path + "]");
		}
		Util.report("See " + IGNORED_BINDINGS_URL + " for an explanation.");

	}

	private static final String STATIC_LOGGER_BINDER_PATH = "org/slf4j/impl/StaticLoggerBinder.class";

	static Set<URL> findPossibleStaticLoggerBinderPathSet() {
		Set<URL> staticLoggerBinderPathSet = new LinkedHashSet<>();
		try {
			ClassLoader loggerFactoryClassLoader = LoggerFactory.class.getClassLoader();
			Enumeration<URL> paths;
			if(loggerFactoryClassLoader == null) {
				paths = ClassLoader.getSystemResources(STATIC_LOGGER_BINDER_PATH);
			} else {
				paths = loggerFactoryClassLoader.getResources(STATIC_LOGGER_BINDER_PATH);
			}
			while(paths.hasMoreElements()) {
				URL path = paths.nextElement();
				staticLoggerBinderPathSet.add(path);
			}
		} catch(IOException ioe) {
			Util.report("Error getting resources from path", ioe);
		}
		return staticLoggerBinderPathSet;
	}

	private static void postBindCleanUp() {
		fixSubstituteLoggers();
		replayEvents();
		SUBST_PROVIDER.getSubstituteLoggerFactory().clear();
	}

	private static void fixSubstituteLoggers() {
		synchronized(SUBST_PROVIDER) {
			SUBST_PROVIDER.getSubstituteLoggerFactory().postInitialization();
			for(SubstituteLogger substLogger : SUBST_PROVIDER.getSubstituteLoggerFactory().getLoggers()) {
				Logger logger = getILoggerFactory().getLogger(substLogger.getName());
				substLogger.setDelegate(logger);
			}
		}

	}

	static void failedBinding(Throwable t) {
		INITIALIZATION_STATE = FAILED_INITIALIZATION;
		Util.report("Failed to instantiate SLF4J LoggerFactory", t);
	}

	private static void replayEvents() {
		final LinkedBlockingQueue<SubstituteLoggingEvent> queue = SUBST_PROVIDER.getSubstituteLoggerFactory().getEventQueue();
		final int queueSize = queue.size();
		int count = 0;
		final int maxDrain = 128;
		List<SubstituteLoggingEvent> eventList = new ArrayList<>(maxDrain);
		while(true) {
			int numDrained = queue.drainTo(eventList, maxDrain);
			if(numDrained == 0) break;
			for(SubstituteLoggingEvent event : eventList) {
				replaySingleEvent(event);
				if(count++ == 0) emitReplayOrSubstituionWarning(event, queueSize);
			}
			eventList.clear();
		}
	}

	private static void emitReplayOrSubstituionWarning(SubstituteLoggingEvent event, int queueSize) {
		if(event.getLogger().isDelegateEventAware()) {
			emitReplayWarning(queueSize);
		} else {
			emitSubstitutionWarning();
		}
	}

	private static void replaySingleEvent(SubstituteLoggingEvent event) {
		if(event == null) return;

		SubstituteLogger substLogger = event.getLogger();
		String loggerName = substLogger.getName();
		if(substLogger.isDelegateNull()) {
			throw new IllegalStateException("Delegate logger cannot be null at this state.");
		}

		if(substLogger.isDelegateEventAware()) {
			substLogger.log(event);
		} else {
			Util.report(loggerName);
		}
	}

	private static void emitSubstitutionWarning() {
		Util.report("The following set of substitute loggers may have been accessed");
		Util.report("during the initialization phase. Logging calls during this");
		Util.report("phase were not honored. However, subsequent logging calls to these");
		Util.report("loggers will work as normally expected.");
		Util.report("See also " + SUBSTITUTE_LOGGER_URL);
	}

	private static void emitReplayWarning(int eventCount) {
		Util.report("A number (" + eventCount + ") of logging calls during the initialization phase have been intercepted and are");
		Util.report("now being replayed. These are subject to the filtering rules of the underlying logging system.");
		Util.report("See also " + REPLAY_URL);
	}

	private static void versionSanityCheck() {
		try {
			String requested = PROVIDER.getRequestedApiVersion();

			boolean match = false;
			for(String aAPI_COMPATIBILITY_LIST : API_COMPATIBILITY_LIST) {
				if(requested.startsWith(aAPI_COMPATIBILITY_LIST)) {
					match = true;
					break;
				}
			}
			if(!match) {
				Util.report("The requested version " + requested + " by your slf4j binding is not compatible with " + Arrays.asList(API_COMPATIBILITY_LIST));
				Util.report("See " + VERSION_MISMATCH + " for further details.");
			}
		} catch(java.lang.NoSuchFieldError ignored) {
		} catch(Throwable e) {
			Util.report("Unexpected problem occured during version sanity check", e);
		}
	}

	private static boolean isAmbiguousProviderList(List<SLF4JServiceProvider> providerList) {
		return providerList.size() > 1;
	}

	private static void reportMultipleBindingAmbiguity(List<SLF4JServiceProvider> providerList) {
		if(isAmbiguousProviderList(providerList)) {
			Util.report("Class path contains multiple SLF4J providers.");
			for(SLF4JServiceProvider provider : providerList) {
				Util.report("Found provider [" + provider + "]");
			}
			Util.report("See " + MULTIPLE_BINDINGS_URL + " for an explanation.");
		}
	}

	private static void reportActualBinding(List<SLF4JServiceProvider> providerList) {
		if(!providerList.isEmpty() && isAmbiguousProviderList(providerList)) {
			Util.report("Actual provider is of type [" + providerList.get(0) + "]");
		}
	}

	public static Logger getLogger(String name) {
		ILoggerFactory iLoggerFactory = getILoggerFactory();
		return iLoggerFactory.getLogger(name);
	}

	public static Logger getLogger(Class<?> clazz) {
		Logger logger = getLogger(clazz.getName());
		if(DETECT_LOGGER_NAME_MISMATCH) {
			Class<?> autoComputedCallingClass = Util.getCallingClass();
			if(autoComputedCallingClass != null && nonMatchingClasses(clazz, autoComputedCallingClass)) {
				Util.report(String.format("Detected logger name mismatch. Given name: \"%s\"; computed name: \"%s\".", logger.getName(), autoComputedCallingClass.getName()));
				Util.report("See " + LOGGER_NAME_MISMATCH_URL + " for an explanation");
			}
		}
		return logger;
	}

	private static boolean nonMatchingClasses(Class<?> clazz, Class<?> autoComputedCallingClass) {
		return !autoComputedCallingClass.isAssignableFrom(clazz);
	}

	public static ILoggerFactory getILoggerFactory() {
		return getProvider().getLoggerFactory();
	}

	static SLF4JServiceProvider getProvider() {
		if(INITIALIZATION_STATE == UNINITIALIZED) {
			synchronized(LoggerFactory.class) {
				if(INITIALIZATION_STATE == UNINITIALIZED) {
					INITIALIZATION_STATE = ONGOING_INITIALIZATION;
					performInitialization();
				}
			}
		}
		switch(INITIALIZATION_STATE) {
			case SUCCESSFUL_INITIALIZATION:
				return PROVIDER;
			case NOP_FALLBACK_INITIALIZATION:
				return NOP_FALLBACK_SERVICE_PROVIDER;
			case FAILED_INITIALIZATION:
				throw new IllegalStateException(UNSUCCESSFUL_INIT_MSG);
			case ONGOING_INITIALIZATION:
				return SUBST_PROVIDER;
		}
		throw new IllegalStateException("Unreachable code");
	}
}
