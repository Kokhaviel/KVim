package fr.kokhaviel.kvim.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.net.URI;

import static java.lang.String.format;

public class KVimAbout extends JFrame {

	public KVimAbout() {
		super("About KVim");
		this.setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		this.setLocationRelativeTo(KVimMain.kVimMain);
		this.setResizable(false);
		this.setIconImage(new ImageIcon(ClassLoader.getSystemResource("kvim/kvim-104x93.png")).getImage());

		final String version = " KVim v1.0.0 ";
		final String runtime = format(" Runtime version : %s %s (%s) ", System.getProperty("java.runtime.version"),
				System.getProperty("os.arch"), System.getProperty("java.home"));
		final String jvm = format(" JVM : %s by %s (at %s) ", System.getProperty("java.vm.name"),
				System.getProperty("java.vm.vendor"), System.getProperty("java.vendor.url"));
		final String os = format(" %s %s (connected as %s) ", System.getProperty("os.name"),
				System.getProperty("os.version"), System.getProperty("user.name"));
		final String copyright = " Copyright (c) Kokhaviel 2022 under Apache License 2.0 ";

		final JLabel github = new JLabel(" Github Repo : https://github.com/Kokhaviel/KVim ");
		github.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent mouseEvent) {
				try {
					Desktop.getDesktop().browse(URI.create("https://github.com/Kokhaviel/KVim"));
				} catch(IOException e) {
					throw new RuntimeException(e);
				}
			}
		});

		JButton copy = new JButton("Copy Information");
		copy.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent mouseEvent) {
				Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(version + "\n" +
						copyright + "\n" + github.getText() + "\n" + runtime + "\n" + jvm + "\n" + os), null);
			}
		});

		this.setLayout(new GridLayout(0, 1));
		this.getContentPane().add(new JLabel(version));
		this.getContentPane().add(new JLabel(copyright));
		this.getContentPane().add(github);
		this.getContentPane().add(new JLabel(runtime));
		this.getContentPane().add(new JLabel(jvm));
		this.getContentPane().add(new JLabel(os));
		this.getContentPane().add(copy);

		this.pack();
	}
}
