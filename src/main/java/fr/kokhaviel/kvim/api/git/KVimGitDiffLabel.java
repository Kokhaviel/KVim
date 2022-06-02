package fr.kokhaviel.kvim.api.git;

import fr.kokhaviel.kvim.gui.KVimMain;
import org.eclipse.jgit.diff.DiffEntry;

import javax.swing.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public class KVimGitDiffLabel extends JButton {

	DiffEntry diffEntry;

	public KVimGitDiffLabel(DiffEntry entry) {
		super(entry.getNewPath());
		this.diffEntry = entry;

		this.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent mouseEvent) {
				JFrame jFrame = new JFrame("Git Diff");
				jFrame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
				jFrame.setLocationRelativeTo(KVimMain.kVimMain);
				jFrame.getContentPane().setLayout(new BoxLayout(jFrame.getContentPane(), BoxLayout.Y_AXIS));
				jFrame.getContentPane().add(new JLabel("[" + entry.getChangeType().name() + "]"));
				jFrame.getContentPane().add(new JLabel(entry.getNewId().name()));
				jFrame.getContentPane().add(new JLabel(entry.getNewMode().toString()));
				jFrame.getContentPane().add(new JLabel(entry.getNewPath()));
				jFrame.getContentPane().add(new JLabel(String.valueOf(entry.getScore())));

				jFrame.pack();
				jFrame.setVisible(true);
			}
		});
	}
}
