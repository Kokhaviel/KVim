package fr.kokhaviel.kvim.gui;

import javax.swing.*;
import java.awt.*;

public class KVimWelcome extends JWindow {

	public KVimWelcome() {
		this.setSize(200, 400);
		this.setLocationRelativeTo(null);
		this.setBackground(new Color(0, 0, 0, 0));
		this.getContentPane().add(new JLabel(new ImageIcon(ClassLoader.getSystemResource("kvim/kvim-104x93.png"))));
	}

	public void splash(int sec) throws InterruptedException {
		this.setVisible(true);
		Thread.sleep(1000L * sec);
		this.setVisible(false);
		this.dispose();
	}
}
