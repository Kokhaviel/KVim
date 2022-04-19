package fr.kokhaviel.kvim.api.actions.file;

import fr.kokhaviel.kvim.gui.KVimMain;

import javax.swing.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import static fr.kokhaviel.kvim.KVim.kVimProperties;

public class KVimClose {

	public static void askExit() {
		int clickedButton = JOptionPane.showConfirmDialog(KVimMain.kVimMain,
				"Are you sure to close app ? All unsaved modifications will be LOST !", "Close KVim x'(", JOptionPane.YES_NO_OPTION);

		if(clickedButton == JOptionPane.YES_OPTION) {
			kVimProperties.getLastParams().replace("height", String.valueOf(KVimMain.kVimMain.getHeight()));
			kVimProperties.getLastParams().replace("width", String.valueOf(KVimMain.kVimMain.getWidth()));
			kVimProperties.getLastParams().replace("x", String.valueOf(KVimMain.kVimMain.getX()));
			kVimProperties.getLastParams().replace("y", String.valueOf(KVimMain.kVimMain.getY()));
			try {
				kVimProperties.getLastParams().store(Files.newOutputStream(Paths.get(kVimProperties.getPropsDir() + "/last_params.properties")), null);
			} catch(IOException ignored) {
			}
			KVimMain.kVimMain.dispose();
			System.exit(0);
		}
	}
}
