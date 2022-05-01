package fr.kokhaviel.kvim.api.actions.file;

import fr.kokhaviel.kvim.KVim;
import fr.kokhaviel.kvim.api.gui.KVimSaveChooser;
import fr.kokhaviel.kvim.api.gui.KVimTab;
import fr.kokhaviel.kvim.gui.KVimMain;

import javax.swing.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static fr.kokhaviel.kvim.gui.KVimMain.tabs;

public class KVimSave {

	public static void saveFile(Path file, String text) {
		try(FileOutputStream fos = new FileOutputStream(String.valueOf(file))) {
			fos.write(text.getBytes(StandardCharsets.UTF_8));
		} catch(IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static void openSaveChooser(KVimTab tab) {
		if(!tab.isUntitled()) {
			saveFile(tab.getFilePath(), tab.getText());
		} else {
			final KVimSaveChooser kVimFileChooser = new KVimSaveChooser();
			int ans = kVimFileChooser.showSaveDialog(KVimMain.kVimMain);

			if(ans == JFileChooser.APPROVE_OPTION) {
				File fileToSave = kVimFileChooser.getSelectedFile();
				final Path toPath = fileToSave.toPath();
				saveFile(toPath, tab.getText());
				tabs.set(tab.getIndex(), new KVimTab(fileToSave.toPath(), tab.getIndex()));
				KVimMain.kVimMain.updateTab(tab.getIndex(), false);
				KVim.kVimProperties.getLastParams().replace("last_save_file", toPath.getParent().toString());
			}
		}
	}
}
