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

public final class Util {

	public static String safeGetSystemProperty(String key) {
		if(key == null)
			throw new IllegalArgumentException("null input");

		String result = null;
		try {
			result = System.getProperty(key);
		} catch(java.lang.SecurityException ignored) {

		}
		return result;
	}

	public static boolean safeGetBooleanSystemProperty(String key) {
		String value = safeGetSystemProperty(key);
		if(value == null)
			return false;
		else
			return value.equalsIgnoreCase("true");
	}

	private static final class ClassContextSecurityManager extends SecurityManager {
		protected Class<?>[] getClassContext() {
			return super.getClassContext();
		}
	}

	private static ClassContextSecurityManager SECURITY_MANAGER;
	private static boolean SECURITY_MANAGER_CREATION_ALREADY_ATTEMPTED = false;

	private static ClassContextSecurityManager getSecurityManager() {
		if(SECURITY_MANAGER != null)
			return SECURITY_MANAGER;
		else if(SECURITY_MANAGER_CREATION_ALREADY_ATTEMPTED)
			return null;
		else {
			SECURITY_MANAGER = safeCreateSecurityManager();
			SECURITY_MANAGER_CREATION_ALREADY_ATTEMPTED = true;
			return SECURITY_MANAGER;
		}
	}

	private static ClassContextSecurityManager safeCreateSecurityManager() {
		try {
			return new ClassContextSecurityManager();
		} catch(java.lang.SecurityException sm) {
			return null;
		}
	}

	public static Class<?> getCallingClass() {
		ClassContextSecurityManager securityManager = getSecurityManager();
		if(securityManager == null)
			return null;
		Class<?>[] trace = securityManager.getClassContext();
		String thisClassName = Util.class.getName();

		int i;
		for(i = 0; i < trace.length; i++) {
			if(thisClassName.equals(trace[i].getName()))
				break;
		}

		if(i >= trace.length || i + 2 >= trace.length) {
			throw new IllegalStateException("Failed to find org.slf4j.helpers.Util or its caller in the stack; " + "this should not happen");
		}

		return trace[i + 2];
	}

	static public void report(String msg, Throwable t) {
		System.err.println(msg);
		System.err.println("Reported exception:");
		t.printStackTrace();
	}

	static public void report(String msg) {
		System.err.println("SLF4J: " + msg);
	}

}
