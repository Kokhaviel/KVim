package fr.kokhaviel.kvim.api.actions.edit;

import fr.kokhaviel.kvim.api.gui.KVimTab;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Document;
import javax.swing.text.Highlighter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

public class KVimFind {

	public static void findOccurrences(KVimTab tab) {
		DefaultHighlighter.DefaultHighlightPainter highlighter = new DefaultHighlighter.DefaultHighlightPainter(new Color(195, 205, 28));
		Highlighter hl = tab.getHighlighter();
		Document doc = tab.getDocument();
		JFrame jFrame = new JFrame("Find occurrences");
		jFrame.setLayout(new FlowLayout(FlowLayout.CENTER));
		final JTextField jTextField = new JTextField();
		final JButton okBtn = new JButton("Ok");

		jTextField.setPreferredSize(new Dimension(120, 25));
		jFrame.add(new JLabel("Text to search : "));
		jFrame.add(jTextField);
		jFrame.add(okBtn);
		jFrame.setLocationRelativeTo(null);
		jFrame.setResizable(false);

		jFrame.pack();
		jFrame.setVisible(true);

		okBtn.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent mouseEvent) {
				if(jTextField.getText() == null || jTextField.getText().equals("")) return;
				try {
					hl.removeAllHighlights();
					String text = doc.getText(0, doc.getLength());
					int pos = 0;

					while((pos = text.toUpperCase().indexOf(jTextField.getText().toUpperCase(), pos)) >= 0) {
						hl.addHighlight(pos, pos + jTextField.getText().length(), highlighter);
						pos += jTextField.getText().length();
					}
				} catch(BadLocationException e) {
					throw new RuntimeException(e);
				}

				if(hl.getHighlights() == null || hl.getHighlights().length == 0)
					JOptionPane.showMessageDialog(jFrame, "There are no matches !", "Find Warning", JOptionPane.WARNING_MESSAGE);
			}
		});

		jFrame.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent windowEvent) {
				hl.removeAllHighlights();
			}
		});
	}
}
