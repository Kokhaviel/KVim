package fr.kokhaviel.kvim.api.actions.edit;

import fr.kokhaviel.kvim.api.gui.KVimTab;

import javax.swing.*;
import javax.swing.text.Document;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public class KVimReplace {

	public static void replaceOccurrences(KVimTab tab) {
		Document doc = tab.getDocument();
		JFrame jFrame = new JFrame("Replace occurrences");
		jFrame.setLayout(new FlowLayout(FlowLayout.CENTER));
		final JTextField from = new JTextField();
		final JTextField to = new JTextField();
		final JCheckBox caseSensitive = new JCheckBox("Case Sensitive ?");
		final JButton okBtn = new JButton("Replace");

		from.setPreferredSize(new Dimension(120, 25));
		to.setPreferredSize(new Dimension(120, 25));
		jFrame.add(new JLabel("Text to replace : "));
		jFrame.add(from);
		jFrame.add(to);
		jFrame.add(caseSensitive);
		jFrame.add(okBtn);
		jFrame.setLocationRelativeTo(null);
		jFrame.setResizable(false);

		jFrame.pack();
		jFrame.setVisible(true);

		okBtn.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent mouseEvent) {
				if(from.getText() == null || to.getText() == null || from.getText().equals(""))
					return;
				String text = tab.getText();
				int pos;
				if(caseSensitive.isSelected()) {
					pos = text.indexOf(from.getText());
				} else {
					pos = text.toUpperCase().indexOf(from.getText().toUpperCase());
				}
				try {
					tab.setSelectionStart(pos);
					tab.setSelectionEnd(pos + from.getText().length());
					tab.replaceSelection(to.getText());
				} catch(IllegalArgumentException e) {
					JOptionPane.showMessageDialog(jFrame, "There are no more matches !", "Replace Warning", JOptionPane.WARNING_MESSAGE);
				}
			}
		});
	}
}
