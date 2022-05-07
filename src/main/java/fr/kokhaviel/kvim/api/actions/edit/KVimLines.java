package fr.kokhaviel.kvim.api.actions.edit;

import fr.kokhaviel.kvim.api.gui.KVimTab;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.Utilities;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public class KVimLines {

	public static int getLineNumber(KVimTab tab) throws BadLocationException {
		return tab.getLineOfOffset(tab.getCaretPosition());
	}

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

	public static void gotoLine(KVimTab tab) throws BadLocationException {
		JFrame jFrame = new JFrame("Goto Line");
		JPanel panel = new JPanel();
		JTextField jtf = new JTextField(String.valueOf(getLineNumber(tab)));
		JButton ok = new JButton("Ok");

		jFrame.setLocationRelativeTo(null);
		panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
		panel.setLayout(new FlowLayout(FlowLayout.CENTER));
		jtf.setPreferredSize(new Dimension(120, 25));
		ok.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent mouseEvent) {
				try {
					int lineIndex = Integer.parseInt(jtf.getText());

					tab.setCaretPosition(
							tab.getDocument().getDefaultRootElement().getElement(lineIndex).getStartOffset());

					jFrame.dispose();
				} catch(NumberFormatException e) {
					JOptionPane.showMessageDialog(jFrame, "Please use a real number", "Not a number ...", JOptionPane.ERROR_MESSAGE);
				}

			}
		});

		panel.add(new JLabel("Line Number"));
		panel.add(jtf);
		panel.add(ok);

		jFrame.add(panel);
		jFrame.pack();

		jFrame.setVisible(true);


	}
}
