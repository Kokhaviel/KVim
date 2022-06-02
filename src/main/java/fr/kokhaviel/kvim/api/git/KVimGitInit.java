package fr.kokhaviel.kvim.api.git;

import fr.kokhaviel.kvim.KVim;
import fr.kokhaviel.kvim.api.gui.KVimTab;
import fr.kokhaviel.kvim.gui.KVimMain;
import fr.kokhaviel.kvim.gui.KVimMenuBar;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;

import javax.swing.*;
import javax.swing.filechooser.FileFilter;
import java.io.File;

import static fr.kokhaviel.kvim.gui.KVimMain.tabs;

public class KVimGitInit {


	public static void initRepo(KVimTab tab) throws GitAPIException {
		JFileChooser fileChooser = new JFileChooser();
		fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		fileChooser.setCurrentDirectory(tab.getFilePath().toFile());
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
		if(ans == JFileChooser.APPROVE_OPTION) {
			if(!fileChooser.getSelectedFile().isDirectory()) return;
			tab.setGitRepository(Git.init().setDirectory(new File(fileChooser.getSelectedFile().getAbsolutePath())).call());
			tab.setHasAGitRepo(true);
			tab.setRootGitPath(fileChooser.getSelectedFile().toPath());
			KVimMain.kVimMain.updateTab(tab.getIndex(), false);
		}
	}
}
