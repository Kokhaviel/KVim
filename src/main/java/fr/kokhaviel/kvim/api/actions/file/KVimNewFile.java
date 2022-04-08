package fr.kokhaviel.kvim.api.actions.file;

import fr.kokhaviel.kvim.api.gui.KVimNewMenuItem;
import fr.kokhaviel.kvim.api.gui.KVimTab;
import fr.kokhaviel.kvim.gui.KVimMain;
import fr.kokhaviel.kvim.gui.KVimNew;

import java.awt.event.ActionEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class KVimNewFile {

	public static void openNewFileWindow(ActionEvent event) {
		KVimNewMenuItem item;
		if(!((event).getSource() instanceof KVimNewMenuItem)) return;
		else item = (KVimNewMenuItem) event.getSource();

		new KVimNew("Create New " + item.getNewFileType().getName() + " File", item.getNewFileType()).setVisible(true);
	}

	public static void createFile(String fileName, String filePath) throws IOException {
		Path filePathPath = Paths.get(filePath);
		Path finalPath = Paths.get(filePathPath + "/" + fileName);

		if(!Files.exists(finalPath)) {
			finalPath.toFile().createNewFile();
		}
	}

	public static void createUntitledTab() {
		KVimMain.tabs.add(new KVimTab(null, KVimMain.tabs.size()));
		KVimMain.kVimMain.updateTab(KVimMain.tabs.size() - 1);
	}

	public static void createNewTab(String path) {
		KVimMain.tabs.add(new KVimTab(Paths.get(path), KVimMain.tabs.size()));
		KVimMain.kVimMain.updateTab(KVimMain.tabs.size() - 1);
	}
}
