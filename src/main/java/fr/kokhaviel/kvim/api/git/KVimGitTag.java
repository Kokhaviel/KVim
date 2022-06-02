package fr.kokhaviel.kvim.api.git;

import fr.kokhaviel.kvim.api.gui.KVimTab;
import fr.kokhaviel.kvim.gui.KVimMain;
import org.eclipse.jgit.api.TagCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.PersonIdent;

import javax.swing.*;

public class KVimGitTag {

	public static void tagCommit(KVimTab tab) throws GitAPIException {
		final String author = JOptionPane.showInputDialog(KVimMain.kVimMain,
				"Who is the author ?", "Commit Author", JOptionPane.QUESTION_MESSAGE);
		final String email = JOptionPane.showInputDialog(KVimMain.kVimMain,
				"And its Email Address ?", "Commit Email", JOptionPane.QUESTION_MESSAGE);
		final String name = JOptionPane.showInputDialog(KVimMain.kVimMain,
				"What is the name of the tag ?", "Tag Name", JOptionPane.QUESTION_MESSAGE);
		final String message = JOptionPane.showInputDialog(KVimMain.kVimMain,
				"What is the message ?", "Commit Message", JOptionPane.QUESTION_MESSAGE);

		final TagCommand tag = tab.getGitRepository().tag();
		tag.setTagger(new PersonIdent(author, email));
		tag.setName(name);
		tag.setMessage(message);
		tag.call();
	}
}
