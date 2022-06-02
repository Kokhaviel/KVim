package fr.kokhaviel.kvim.api.git;

import fr.kokhaviel.kvim.api.gui.KVimTab;
import org.eclipse.jgit.api.errors.GitAPIException;

public class KVimGitRemoveCached {

	public static void removeCached(KVimTab tab) throws GitAPIException {
		tab.getGitRepository().rm().setCached(true).addFilepattern(
				tab.getRootGitPath().toUri().relativize(tab.getFilePath().toUri()).getPath()).call();
	}
}
