package fr.kokhaviel.kvim.api.git;

import fr.kokhaviel.kvim.api.gui.KVimTab;
import org.eclipse.jgit.api.errors.GitAPIException;

public class KVimGitPull {

	public static void pull(KVimTab tab) throws GitAPIException {
		tab.getGitRepository().pull().call();
	}
}
