package fr.kokhaviel.kvim.api.git;

import fr.kokhaviel.kvim.api.gui.KVimTab;
import fr.kokhaviel.kvim.gui.KVimMain;
import org.eclipse.jgit.api.errors.GitAPIException;

import javax.swing.*;

public class KVimGitBranchDelete {

	public static void branchDelete(KVimTab tab) throws GitAPIException {
		String branch = JOptionPane.showInputDialog(KVimMain.kVimMain, "Choose a branch to delete : ", "Delete Branch", JOptionPane.QUESTION_MESSAGE);
		tab.getGitRepository().branchDelete().setBranchNames(branch).call();

		KVimMain.kVimMain.updateTab(tab.getIndex(), false);
	}
}
