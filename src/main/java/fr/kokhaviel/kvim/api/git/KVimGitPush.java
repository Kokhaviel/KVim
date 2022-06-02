package fr.kokhaviel.kvim.api.git;

import fr.kokhaviel.kvim.api.gui.KVimTab;
import fr.kokhaviel.kvim.gui.KVimMain;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.transport.URIish;

import javax.swing.*;
import java.net.URISyntaxException;

public class KVimGitPush {

	public static void pushCommits(KVimTab tab) throws GitAPIException, URISyntaxException {
		if(tab.getGitRepository().remoteList().call().isEmpty()) {
			String remoteName = JOptionPane.showInputDialog(KVimMain.kVimMain,
					"You need to create a remote to push commits. Choose a name : ", "No Remote Available",
					JOptionPane.WARNING_MESSAGE);
			String remoteURL = JOptionPane.showInputDialog(KVimMain.kVimMain, "And an URL for this remote : ",
					"New Remote URL", JOptionPane.QUESTION_MESSAGE);

			tab.getGitRepository().remoteAdd().setName(remoteName).setUri(new URIish(remoteURL));


		}

		try {
			tab.getGitRepository().push().call();
		} catch(TransportException e) {

			JOptionPane.showMessageDialog(KVimMain.kVimMain,
					"Authentication is required. This application doesn't provide authenticator. " +
							"You will need to push by the command line", "Auth Required",
					JOptionPane.ERROR_MESSAGE);
		}
	}
}
