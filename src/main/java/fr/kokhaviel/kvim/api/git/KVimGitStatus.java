package fr.kokhaviel.kvim.api.git;

import fr.kokhaviel.kvim.api.gui.KVimTab;
import fr.kokhaviel.kvim.gui.KVimMain;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;

import javax.swing.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Set;

public class KVimGitStatus {

	public static void showStatus(KVimTab tab) throws GitAPIException {
		if(!tab.hasAGitRepo()) return;
		final Status status = tab.getGitRepository().status().call();

		JFrame jFrame = new JFrame("Git Status");
		jFrame.setLocationRelativeTo(KVimMain.kVimMain);
		jFrame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBorder(BorderFactory.createEmptyBorder(20, 35, 20, 35));

		final JButton added = new JButton("Added Files");
		final JButton changed = new JButton("Changed Files");
		final JButton conflicting = new JButton("Conflicting Files");
		final JButton ignored = new JButton("Ignored Files");
		final JButton missing = new JButton("Missing Files");
		final JButton modified = new JButton("Modified Files");
		final JButton removed = new JButton("Removed Files");
		final JButton uncommitted = new JButton("Uncommitted Files");
		final JButton untracked = new JButton("Untracked Folders");

		added.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent mouseEvent) {
				final Set<String> statusAdded = status.getAdded();
				JFrame jFrame1 = getStatusJFrame(statusAdded, jFrame, "Added Files");

				jFrame1.setVisible(true);
			}
		});

		changed.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent mouseEvent) {
				final Set<String> statusChanged = status.getChanged();
				JFrame jFrame1 = getStatusJFrame(statusChanged, jFrame, "Changed Files");

				jFrame1.setVisible(true);
			}
		});

		conflicting.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent mouseEvent) {
				final Set<String> statusConflicting = status.getConflicting();
				JFrame jFrame1 = getStatusJFrame(statusConflicting, jFrame, "Conflicting Files");

				jFrame1.setVisible(true);
			}
		});

		ignored.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent mouseEvent) {
				final Set<String> statusIgnored = status.getIgnoredNotInIndex();
				JFrame jFrame1 = getStatusJFrame(statusIgnored, jFrame, "Ignored Files");

				jFrame1.setVisible(true);
			}
		});

		missing.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent mouseEvent) {
				final Set<String> statusMissing = status.getMissing();
				JFrame jFrame1 = getStatusJFrame(statusMissing, jFrame, "Missing Files");

				jFrame1.setVisible(true);
			}
		});

		modified.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent mouseEvent) {
				final Set<String> statusModified = status.getModified();
				JFrame jFrame1 = getStatusJFrame(statusModified, jFrame, "Modified Files");

				jFrame1.setVisible(true);
			}
		});

		removed.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent mouseEvent) {
				final Set<String> statusRemoved = status.getRemoved();
				JFrame jFrame1 = getStatusJFrame(statusRemoved, jFrame, "Removed Files");

				jFrame1.setVisible(true);
			}
		});

		uncommitted.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent mouseEvent) {
				final Set<String> statusUncommitted = status.getUncommittedChanges();
				JFrame jFrame1 = getStatusJFrame(statusUncommitted, jFrame, "Uncommitted Files");

				jFrame1.setVisible(true);
			}
		});

		untracked.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent mouseEvent) {
				final Set<String> statusUntracked = status.getUntracked();
				JFrame jFrame1 = getStatusJFrame(statusUntracked, jFrame, "Untracked Files");

				jFrame1.setVisible(true);
			}
		});

		panel.add(new JLabel(status.isClean() ? "Repository is clean  " : "Repository have changes  "));
		panel.add(new JLabel("   "));
		panel.add(added);
		panel.add(changed);
		panel.add(conflicting);
		panel.add(ignored);
		panel.add(missing);
		panel.add(modified);
		panel.add(removed);
		panel.add(uncommitted);
		panel.add(untracked);

		jFrame.getContentPane().add(panel);

		jFrame.pack();
		jFrame.setResizable(false);
		jFrame.setVisible(true);
	}

	public static JFrame getStatusJFrame(Set<String> files, JFrame relativeTo, String title) {
		JFrame jFrame1 = new JFrame(title);

		jFrame1.setLocationRelativeTo(relativeTo);
		jFrame1.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBorder(BorderFactory.createEmptyBorder(20, 35, 20, 35));

		files.forEach(s -> panel.add(new JLabel(s)));

		jFrame1.getContentPane().add(panel);

		jFrame1.pack();
		jFrame1.setResizable(false);

		return jFrame1;
	}
}
