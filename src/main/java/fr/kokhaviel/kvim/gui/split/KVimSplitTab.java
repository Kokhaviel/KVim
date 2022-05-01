package fr.kokhaviel.kvim.gui.split;

import fr.kokhaviel.kvim.api.gui.KVimTab;
import fr.kokhaviel.kvim.gui.KVimMain;
import fr.kokhaviel.kvim.gui.KVimMenuBar;

import javax.swing.*;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public class KVimSplitTab extends JPanel {

	public static KVimTab curLeftTab;
	public static KVimTab curRightTab;
	public static SplitOrientation curOrientation;

	public KVimSplitTab(KVimTab leftTab, KVimTab rightTab, SplitOrientation orientation) {

		curOrientation = orientation;
		leftTab.addFocusListener(new FocusAdapter() {
			@Override
			public void focusGained(FocusEvent focusEvent) {
				KVimMain.kVimMain.setJMenuBar(new KVimMenuBar(leftTab));
			}
		});

		rightTab.addFocusListener(new FocusAdapter() {
			@Override
			public void focusGained(FocusEvent focusEvent) {
				KVimMain.kVimMain.setJMenuBar(new KVimMenuBar(rightTab));
			}
		});

		switch(orientation) {
			case VERTICAL:
				this.setLayout(new GridLayout(1, 2));
				break;
			case HORIZONTAL:
				this.setLayout(new GridLayout(2, 1));
				break;
			default:
				return;
		}

		JPanel leftPanel  = new JPanel(new BorderLayout());
		JPanel rightPanel = new JPanel(new BorderLayout());

		JPanel topLeftPanel  = new JPanel(new BorderLayout());
		JPanel topRightPanel = new JPanel(new BorderLayout());

		final JButton leftCloseBtn = new JButton("X");
		final JButton rightCloseBtn = new JButton("X");

		leftCloseBtn.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent mouseEvent) {
				KVimMain.kVimMain.updateTab(rightTab.getIndex(), false);
			}
		});

		rightCloseBtn.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent mouseEvent) {
				KVimMain.kVimMain.updateTab(leftTab.getIndex(), false);
			}
		});

		//TODO : Add Icons instead of letters X
		topLeftPanel.add(new KVimSplitTabNav(KVimSplitTabNav.SplitSide.LEFT), BorderLayout.CENTER);
		topLeftPanel.add(leftCloseBtn, BorderLayout.EAST);

		topRightPanel.add(new KVimSplitTabNav(KVimSplitTabNav.SplitSide.RIGHT), BorderLayout.CENTER);
		topRightPanel.add(rightCloseBtn, BorderLayout.EAST);

		leftPanel.add(topLeftPanel, BorderLayout.NORTH);
		rightPanel.add(topRightPanel, BorderLayout.NORTH);

		leftPanel.add(new JScrollPane(leftTab), BorderLayout.CENTER);
		rightPanel.add(new JScrollPane(rightTab), BorderLayout.CENTER);

		this.add(leftPanel);
		this.add(rightPanel);
	}

	public enum SplitOrientation {
		VERTICAL,
		HORIZONTAL
	}
}
