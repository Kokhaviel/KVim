package fr.kokhaviel.kvim.api.gui;

import fr.kokhaviel.kvim.api.FileType;
import fr.kokhaviel.kvim.api.actions.file.KVimNewFile;

import javax.swing.*;

public class KVimNewMenuItem extends KVimMenuItem {

	FileType newFile;

	public KVimNewMenuItem(String title, FileType fileType) {
		super(title);
		this.newFile = fileType;
		initItem();
	}

	public KVimNewMenuItem(String title, Icon icon, FileType newFile) {
		super(title, icon);
		this.setIcon(icon);
		this.newFile = newFile;
		initItem();
	}

	public void initItem() {
		this.addActionListener(KVimNewFile::openNewFileWindow);
	}

	public FileType getNewFileType() {
		return newFile;
	}
}
