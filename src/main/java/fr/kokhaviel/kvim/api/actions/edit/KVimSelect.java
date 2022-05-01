package fr.kokhaviel.kvim.api.actions.edit;

import fr.kokhaviel.kvim.api.gui.KVimTab;

public class KVimSelect {

	public static void selectAll(KVimTab tab) {
		tab.requestFocus();
		tab.selectAll();
	}

	public static void deselectAll(KVimTab tab) {
		tab.requestFocus();
		tab.setCaretPosition(0);
	}

	public static void cut(KVimTab tab) {
		tab.cut();
	}

	public static void copy(KVimTab tab) {
		tab.copy();
	}

	public static void paste(KVimTab tab) {
		tab.paste();
	}
}
