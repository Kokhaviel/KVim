package fr.kokhaviel.kvim.api.git;

import fr.kokhaviel.kvim.api.gui.KVimTab;
import fr.kokhaviel.kvim.gui.KVimMain;
import org.eclipse.jgit.api.errors.GitAPIException;

import javax.swing.*;

public class KvimGitBranchAdd {

	public static void branchAdd(KVimTab tab) throws GitAPIException {
		String name = JOptionPane.showInputDialog(KVimMain.kVimMain, "Choose a branch name : ", "Add a Branch", JOptionPane.QUESTION_MESSAGE);

		tab.getGitRepository().branchCreate().setName(name).call();

		KVimMain.kVimMain.updateTab(tab.getIndex(), false);
	}
}
