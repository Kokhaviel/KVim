package fr.kokhaviel.kvim.api.git;

import fr.kokhaviel.kvim.api.gui.KVimTab;
import fr.kokhaviel.kvim.gui.KVimMain;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;

import javax.swing.*;
import java.util.List;

public class KVimGitDiff {

	public static void showDiffs(KVimTab tab) throws GitAPIException {
		final List<DiffEntry> call = tab.getGitRepository().diff().call();

		JFrame jFrame = new JFrame("Diffs");
		jFrame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		jFrame.setLocationRelativeTo(KVimMain.kVimMain);
		jFrame.getContentPane().setLayout(new BoxLayout(jFrame.getContentPane(), BoxLayout.Y_AXIS));
		call.forEach(entry -> {
			jFrame.getContentPane().add(new KVimGitDiffLabel(entry));
		});


		jFrame.pack();
		jFrame.setVisible(true);
	}
}
