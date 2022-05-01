package fr.kokhaviel.kvim.api.actions.file;

import fr.kokhaviel.kvim.api.gui.KVimSaveChooser;
import fr.kokhaviel.kvim.api.gui.KVimTab;
import fr.kokhaviel.kvim.gui.KVimMain;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static fr.kokhaviel.kvim.gui.KVimMain.tabs;

public class KVimFile {

	public static void moveFile(File src, File target) throws IOException {
		if(src == null || target == null) return;
		Files.move(src.toPath(), target.toPath());
	}

	public static void copyFile(File src, File target) throws IOException {
		if(src == null || target == null) return;
		Files.copy(src.toPath(), target.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
	}

	public static void deleteFile(File src) throws IOException {
		if(src == null) return;
		Files.delete(src.toPath());
	}

	public static File openSaveChooser(Path srcPath) {
		final String caller = Thread.currentThread().getStackTrace()[2].getMethodName();
		final KVimSaveChooser kVimFileChooser = new KVimSaveChooser();
		if(caller.equals("moveFile")) kVimFileChooser.setDialogTitle("Move a File");
		else if(caller.equals("copyFile")) kVimFileChooser.setDialogTitle("Copy a File");
		kVimFileChooser.setCurrentDirectory(srcPath.toFile());
		int ans = kVimFileChooser.showSaveDialog(KVimMain.kVimMain);

		if(ans == JFileChooser.APPROVE_OPTION) {
			return kVimFileChooser.getSelectedFile();
		}

		return null;
	}

	public static void moveFile(KVimTab tab) throws IOException {
		final File file = openSaveChooser(tab.getFilePath());
		moveFile(tab.getFilePath().toFile(), file);

		if(file == null) return;
		tabs.set(tab.getIndex(), new KVimTab(file.toPath(), tab.getIndex()));
		KVimMain.kVimMain.updateTab(tab.getIndex(), false);
	}

	public static void copyFile(KVimTab tab) throws IOException {
		final File file = openSaveChooser(tab.getFilePath());
		copyFile(tab.getFilePath().toFile(), file);

		if(file == null) return;
		final KVimTab kVimTab = new KVimTab(file.toPath(), tab.getIndex() + 1);
		kVimTab.setText(KVimOpen.getFileContent(kVimTab.getFilePath()));
		tabs.add(tab.getIndex() + 1, kVimTab);
		KVimMain.kVimMain.updateTab(tab.getIndex(), false);
	}

	public static void deleteFile(KVimTab tab) throws IOException {
		int clickedButton = JOptionPane.showConfirmDialog(null,
				"Are you sure to delete "+ tab.getFilename() + " ? " +
						"All unsaved modifications will be LOST !", "Delete File", JOptionPane.YES_NO_OPTION);

		if(clickedButton == JOptionPane.YES_OPTION) {
			deleteFile(tab.getFilePath().toFile());
			tabs.remove(tab.getIndex());
			KVimMain.kVimMain.updateTab(tab.getIndex() - 1, false);
		}
	}
}
