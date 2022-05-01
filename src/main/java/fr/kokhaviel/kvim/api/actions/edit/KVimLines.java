package fr.kokhaviel.kvim.api.actions.edit;

import fr.kokhaviel.kvim.api.gui.KVimTab;

import javax.swing.text.BadLocationException;
import javax.swing.text.Utilities;

public class KVimLines {

	public static void deleteLine(KVimTab tab) throws BadLocationException {
		int offset = tab.getCaretPosition();
		int start = Utilities.getRowStart(tab, offset);
		int end = Utilities.getRowEnd(tab, offset);
		tab.replaceRange("", start, end);
	}

	public static void duplicateLine(KVimTab tab) throws BadLocationException {
		int offset = tab.getCaretPosition();
		int start = Utilities.getRowStart(tab, offset);
		int end = Utilities.getRowEnd(tab, offset);

		if(tab.getSelectedText() != null && !tab.getSelectedText().equals("")) {
			tab.insert(tab.getSelectedText(), tab.getSelectionEnd());
		} else {
			tab.insert("\n" + tab.getText(start, end), end);
		}
	}

	public static void swapUpLine(KVimTab tab) throws BadLocationException {
		tab.requestFocus();
		int offset = tab.getCaretPosition();
		int start = Utilities.getRowStart(tab, offset);
		int end = Utilities.getRowEnd(tab, offset);
		offset = start - 1;
		int insertPos = Utilities.getRowStart(tab, offset);
		if(insertPos < 0) insertPos = 0;

		final String toMove = tab.getText(start, end - start);
		tab.replaceRange("", start - 1, end);
		tab.insert(toMove + "\n", insertPos);
	}

	public static void swapDownLine(KVimTab tab) throws BadLocationException {
		tab.requestFocus();
		int offset = tab.getCaretPosition();
		int start = Utilities.getRowStart(tab, offset);
		int end = Utilities.getRowEnd(tab, offset);
		offset = end + 1;
		int insertPos = Utilities.getRowEnd(tab, offset);
		if(insertPos > tab.getText().length()) insertPos = tab.getText().length();

		final String toMove = tab.getText(start, end - start);
		System.out.println(toMove);
		tab.replaceRange("", start - 1, end);
		tab.insert(toMove + "\n" , insertPos - toMove.length());

	}
}
