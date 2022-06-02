package fr.kokhaviel.kvim.gui;

import fr.kokhaviel.kvim.api.actions.edit.KVimLines;
import fr.kokhaviel.kvim.api.gui.KVimTab;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import java.awt.*;

public class KVimSideBar extends JPanel {


	public KVimSideBar(KVimTab tab) throws BadLocationException {
		JLabel jLabel = new JLabel("Line " + KVimLines.getLineNumber(tab) + " of " + (tab.getText().split("\n").length + 1)
										+ ", Character " + tab.getCaretPosition() + " of " + tab.getText().length());
		this.setLayout(new FlowLayout(FlowLayout.RIGHT));
		this.add(jLabel);
	}
}
