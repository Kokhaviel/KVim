package fr.kokhaviel.kvim.gui.split;

import fr.kokhaviel.kvim.gui.KVimMain;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.concurrent.atomic.AtomicInteger;

import static fr.kokhaviel.kvim.gui.split.KVimSplitTab.curOrientation;

public class KVimSplitTabNav extends JPanel {

	private static class KVimSplitTabButton extends JButton {
		public KVimSplitTabButton(String s, int index, SplitSide side) {
			super(s);

			this.addMouseListener(new MouseAdapter() {
				@Override
				public void mouseClicked(MouseEvent mouseEvent) {
					switch(side) {
						case LEFT:
							if(index == KVimSplitTab.curRightTab.getIndex()) break;
							KVimMain.kVimMain.updateSplit(index, KVimSplitTab.curRightTab.getIndex(), curOrientation);
							break;
						case RIGHT:
							if(index == KVimSplitTab.curLeftTab.getIndex()) break;
							KVimMain.kVimMain.updateSplit(KVimSplitTab.curLeftTab.getIndex(), index, curOrientation);
							break;
					}
				}
			});
		}
	}

	public enum SplitSide {
		LEFT,
		RIGHT
	}

	public KVimSplitTabNav(SplitSide side) {
		super(new GridLayout(1, KVimMain.tabs.size()));
		KVimMain.tabs.forEach(tab -> {
			KVimSplitTabButton button = new KVimSplitTabButton(tab.getFilename(), tab.getIndex(), side);
			KVimSplitTabNav.this.add(button);
		});
	}
}
