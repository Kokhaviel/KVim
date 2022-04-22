package fr.kokhaviel.kvim.api.actions.file;

import fr.kokhaviel.kvim.api.actions.RecentFile;
import fr.kokhaviel.kvim.api.gui.KVimTab;
import fr.kokhaviel.kvim.gui.KVimMain;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class KVimRecentFile extends JMenuItem {

	public KVimRecentFile(String title, Path path) {
		super(title);
		Path tabPath = Paths.get(path + "/" + title);
		this.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				final KVimTab tab = new KVimTab(tabPath, KVimMain.tabs.size());

				try {
					tab.setText(KVimOpen.getFileContent(tabPath));
				} catch(IOException e) {
					throw new RuntimeException(e);
				}

				KVimMain.tabs.add(tab);
				KVimOpen.updateRecent(new RecentFile(tabPath.toFile().getName(), tabPath.getParent()));
				KVimMain.kVimMain.updateTab(tab.getIndex(), true);
			}
		});

	}
}
