package fr.kokhaviel.kvim.api.actions.file;

import fr.kokhaviel.kvim.api.gui.KVimTab;

import javax.swing.*;
import java.io.IOException;

public class KVimReload {

	public static void reloadFile(KVimTab tab) throws IOException {
		final String fileContent = KVimOpen.getFileContent(tab.getFilePath());
		int clickedButton = JOptionPane.YES_OPTION;
		if(!tab.getBaseText().equals(tab.getText())) {
			clickedButton = JOptionPane.showConfirmDialog(null,
					"Are you sure to reload "+ tab.getFilename() +
							" ? All unsaved modifications will be LOST !", "Reload " + tab.getFilename(),
					JOptionPane.YES_NO_OPTION);
		}

		if(clickedButton == JOptionPane.YES_OPTION) {
			tab.setText(fileContent);
			tab.setBaseText(fileContent);
			tab.setCaretPosition(0);
		}
	}
}
