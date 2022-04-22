package fr.kokhaviel.kvim.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.concurrent.atomic.AtomicInteger;

public class KVimTabNav extends JPanel {

	private static class KVimTabButton extends JButton {

		public KVimTabButton(String s, int index) {
			super(s);

			this.addMouseListener(new MouseAdapter() {
				@Override
				public void mouseClicked(MouseEvent mouseEvent) {
					KVimMain.kVimMain.updateTab(index, false);
				}
			});
		}
	}

	public KVimTabNav() {
		super(new GridLayout(1, KVimMain.tabs.size()));
		AtomicInteger i = new AtomicInteger();
		KVimMain.tabs.forEach(tab -> {
			JButton button = new KVimTabButton(tab.getFilename(), i.get());
			KVimTabNav.this.add(button);
			i.getAndIncrement();
		});
	}
}
