package fr.kokhaviel.kvim.api.actions.edit;

import fr.kokhaviel.kvim.api.gui.KVimTab;
import fr.kokhaviel.kvim.gui.KVimMain;

import static fr.kokhaviel.kvim.gui.KVimMain.tabs;

public class KVimCloseView {

	public static void closeCurrentView(KVimTab tab) {
		tabs.remove(tab.getIndex());
		final int i = Math.max(tab.getIndex() - 1, 0);
		KVimMain.kVimMain.updateTab(i, false);
	}
}
