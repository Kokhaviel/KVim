package fr.kokhaviel.kvim.api.actions.file;

import fr.kokhaviel.kvim.KVim;
import fr.kokhaviel.kvim.api.actions.RecentFile;
import fr.kokhaviel.kvim.api.gui.KVimFileChooser;
import fr.kokhaviel.kvim.api.gui.KVimTab;
import fr.kokhaviel.kvim.gui.KVimMain;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class KVimOpen {

	public static void updateRecent(RecentFile newFile) {
		for(int i = 5; i > 1; i--) {
			KVim.kVimProperties.getRecentFiles().replace("name_" + i,
					KVim.kVimProperties.getRecentFiles().getProperty("name_" + (i - 1)));
			KVim.kVimProperties.getRecentFiles().replace("path_" + i,
					KVim.kVimProperties.getRecentFiles().getProperty("path_" + (i - 1)));

		}

		KVim.kVimProperties.getRecentFiles().replace("name_1", newFile.getName());
		KVim.kVimProperties.getRecentFiles().replace("path_1", newFile.getPath().toString());

		try {
			KVim.kVimProperties.getRecentFiles().store(Files.newOutputStream(Paths.get(KVim.kVimProperties.getPropsDir() + "/recent.properties")), null);
		} catch(IOException e) {
			e.printStackTrace();
		}
	}

	public static void openFile() {
		final KVimFileChooser kVimFileChooser = new KVimFileChooser();
		int ans = kVimFileChooser.showOpenDialog(KVimMain.kVimMain);

		if (ans == JFileChooser.APPROVE_OPTION) {
			File file = kVimFileChooser.getSelectedFile();
			final Path toPath = file.toPath();
			final KVimTab tab = new KVimTab(toPath, KVimMain.tabs.size());

			try {
				tab.setText(KVimOpen.getFileContent(toPath));
			} catch(IOException e) {
				throw new RuntimeException(e);
			}

			KVimMain.tabs.add(tab);
			KVimOpen.updateRecent(new RecentFile(toPath.toFile().getName(), toPath.getParent()));
			KVim.kVimProperties.getLastParams().replace("last_open_file", toPath.getParent().toString());
			KVimMain.kVimMain.updateTab(tab.getIndex(), true);
		}

	}

	public static String getFileContent(Path file) throws IOException {
		StringBuilder builder = new StringBuilder();
		Files.readAllLines(file).forEach(line -> builder.append(line).append("\n"));

		return builder.toString();
	}
}
