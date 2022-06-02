package fr.kokhaviel.kvim.api.git;

import fr.kokhaviel.kvim.api.gui.KVimTab;
import fr.kokhaviel.kvim.gui.KVimMain;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.revwalk.RevCommit;

import javax.swing.*;
import java.awt.*;
import java.util.Arrays;
import java.util.Objects;

public class KVimGitLog {

	public static void showLog(KVimTab tab) throws GitAPIException {
		if(!tab.hasAGitRepo()) return;
		Iterable<RevCommit> log = tab.getGitRepository().log().call();

		JFrame jFrame = new JFrame("Git Log");
		jFrame.setLocationRelativeTo(KVimMain.kVimMain);
		jFrame.getContentPane().setLayout(new BorderLayout());

		JPanel midPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
		midPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		JPanel timePanel = new JPanel(new GridLayout(0, 1));
		JPanel authorNamePanel = new JPanel(new GridLayout(0, 1));
		JPanel authorEmailPanel = new JPanel(new GridLayout(0, 1));
		JPanel messagePanel = new JPanel(new GridLayout(0, 1));



		log.forEach(revCommit -> {
			final JLabel time = new JLabel(Objects.requireNonNull(revCommit.getAuthorIdent()).getWhen().toString());
			final JLabel name = new JLabel(Objects.requireNonNull(revCommit.getAuthorIdent()).getName());
			final JLabel email = new JLabel(Objects.requireNonNull(revCommit.getAuthorIdent()).getEmailAddress());
			final JLabel message = new JLabel(revCommit.getFullMessage());

			Arrays.asList(time, name, email, message).forEach(
					jLabel -> jLabel.setBorder(BorderFactory.createMatteBorder(1, 1, 1, 1, Color.BLACK))
			);

			timePanel.add(time);
			authorNamePanel.add(name);
			authorEmailPanel.add(email);
			messagePanel.add(message);
		});

		midPanel.add(timePanel);
		midPanel.add(authorNamePanel);
		midPanel.add(authorEmailPanel);
		midPanel.add(messagePanel);

		jFrame.getContentPane().add(new JScrollPane(midPanel), BorderLayout.CENTER);
		jFrame.setSize(400, 250);
		jFrame.setVisible(true);
	}
}
