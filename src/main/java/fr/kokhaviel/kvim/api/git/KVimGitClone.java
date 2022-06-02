package fr.kokhaviel.kvim.api.git;

import fr.kokhaviel.kvim.gui.KVimMain;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;

import javax.swing.*;
import javax.swing.filechooser.FileFilter;
import java.io.File;

public class KVimGitClone {

	public static void cloneRepo() throws GitAPIException {
		JFileChooser fileChooser = new JFileChooser();
		fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		fileChooser.setDialogTitle("Choose a Git Root Directory");
		fileChooser.setFileFilter(new FileFilter() {
			@Override
			public boolean accept(File file) {
				return file.isDirectory();
			}

			@Override
			public String getDescription() {
				return "Directory";
			}
		});
		int ans = fileChooser.showOpenDialog(KVimMain.kVimMain);
		String url = JOptionPane.showInputDialog(KVimMain.kVimMain, "Enter the repo URL : ", "Clone Repo", JOptionPane.QUESTION_MESSAGE);
		if(ans == JFileChooser.APPROVE_OPTION) {
			if(!fileChooser.getSelectedFile().isDirectory()) return;
			Git.cloneRepository().setDirectory(fileChooser.getSelectedFile()).setURI(url)
					.setCloneAllBranches(true).call().close();
		}
	}
}
