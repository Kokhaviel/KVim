package fr.kokhaviel.kvim.api.git;

import fr.kokhaviel.kvim.api.gui.KVimTab;
import org.eclipse.jgit.api.errors.GitAPIException;

public class KVimGitFetch {

	public static void fetch(KVimTab tab) throws GitAPIException {
		tab.getGitRepository().fetch().call();
	}
}
