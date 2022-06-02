package fr.kokhaviel.kvim.api.git;

import fr.kokhaviel.kvim.api.gui.KVimTab;
import fr.kokhaviel.kvim.gui.KVimMain;
import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.api.errors.GitAPIException;

import javax.swing.*;

public class KVimGitCommit {

	public static void commit(KVimTab tab) throws GitAPIException {
		final String author = JOptionPane.showInputDialog(KVimMain.kVimMain,
				"Who is the author ?", "Commit Author", JOptionPane.QUESTION_MESSAGE);
		final String email = JOptionPane.showInputDialog(KVimMain.kVimMain,
				"And its Email Address ?", "Commit Email", JOptionPane.QUESTION_MESSAGE);
		final String message = JOptionPane.showInputDialog(KVimMain.kVimMain,
				"What is the message ?", "Commit Message", JOptionPane.QUESTION_MESSAGE);
		CommitCommand commit = tab.getGitRepository().commit();
		commit.setAuthor(author, email);
		commit.setCommitter(author, email);
		commit.setMessage(message);
		commit.call();
	}
}
