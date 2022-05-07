package fr.kokhaviel.kvim.api.actions.todos;

import javax.swing.*;
import java.awt.*;

public class KVimTODOShow extends JFrame {

	public KVimTODOShow(KVimTODOItem item) throws HeadlessException {
		super("TODO Task " + item.getName());
		this.setLayout(new GridLayout(1, 2));
		this.setLocationRelativeTo(null);
		this.setIconImage(new ImageIcon(ClassLoader.getSystemResource("kvim/kvim-104x93.png")).getImage());

		JPanel jPanel = new JPanel(new BorderLayout());

		JPanel left  = new JPanel(new GridLayout(0, 1));
		JPanel right = new JPanel(new GridLayout(0, 1));

		left.add(new JLabel("Name : "));
		left.add(new JLabel("In File : "));
		left.add(new JLabel("Contents :  "));

		right.add(new JLabel(item.getName()));
		right.add(new JLabel(item.getRelativePath()));
		right.add(new JLabel(item.getContents()));

		jPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		jPanel.add(left, BorderLayout.WEST);
		jPanel.add(right, BorderLayout.EAST);

		this.getContentPane().add(jPanel);

		pack();
	}
}
