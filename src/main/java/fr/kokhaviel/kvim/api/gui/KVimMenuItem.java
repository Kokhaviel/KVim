package fr.kokhaviel.kvim.api.gui;

import javax.swing.*;

public class KVimMenuItem extends JMenuItem {


	public KVimMenuItem(String title) {
		super(title);
	}

	public KVimMenuItem(String title, Icon icon) {
		super(title);
		this.setIcon(icon);
	}
}
