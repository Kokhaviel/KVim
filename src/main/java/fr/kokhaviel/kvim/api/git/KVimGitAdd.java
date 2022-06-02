package fr.kokhaviel.kvim.api.git;

import fr.kokhaviel.kvim.api.gui.KVimTab;
import org.eclipse.jgit.api.errors.GitAPIException;

public class KVimGitAdd {

	public static void addFile(KVimTab tab) throws GitAPIException {
		tab.getGitRepository().add().addFilepattern(
				tab.getRootGitPath().toUri().relativize(tab.getFilePath().toUri()).getPath()).call();
	}
}
