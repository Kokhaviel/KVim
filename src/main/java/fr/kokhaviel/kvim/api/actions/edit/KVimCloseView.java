package fr.kokhaviel.kvim.api.actions.edit;

import fr.kokhaviel.kvim.api.gui.KVimTab;
import fr.kokhaviel.kvim.gui.KVimMain;
import fr.kokhaviel.kvim.gui.KVimMenuBar;

import static fr.kokhaviel.kvim.gui.KVimMain.tabs;

public class KVimCloseView {

	public static void closeCurrentView(KVimTab tab) {
		if(tab.hasAGitRepo()) tab.getGitRepository().close();
		tabs.remove(tab.getIndex());
		KVimMain.kVimMain.updateTab(Math.max(tab.getIndex() - 1, 0), false);
	}

	public static void closeOthersView(KVimTab tab) {
		tabs.clear();
		tab.setIndex(0);
		tabs.add(tab);
		KVimMain.kVimMain.updateTab(0, false);
	}
}
