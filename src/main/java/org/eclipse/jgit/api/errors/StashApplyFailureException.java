package org.eclipse.jgit.api.errors;

public class StashApplyFailureException extends GitAPIException {

	private static final long serialVersionUID = 1L;

	public StashApplyFailureException(String message, Throwable cause) {
		super(message, cause);
	}

	public StashApplyFailureException(String message) {
		super(message);
	}

}
