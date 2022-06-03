package fr.kokhaviel.kvim.gui;

import fr.kokhaviel.kvim.api.FileType;
import fr.kokhaviel.kvim.api.actions.file.KVimNewFile;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;

public class KVimNew extends JFrame {

	JButton cancel = new JButton("Cancel");
	JButton ok = new JButton("Create");

	public KVimNew(String title, FileType fileType) {
		super(title);

		this.setIconImage(new ImageIcon(fileType.getLangImgUri()).getImage());

		JTextField name = new JTextField();
		JTextField path = new JTextField();
		JLabel nameLabel = new JLabel("Name : ");
		JLabel pathLabel = new JLabel("Path : ");
		JPanel jPanel = new JPanel(new BorderLayout());
		JPanel namePanel = new JPanel(new BorderLayout());
		JPanel pathPanel = new JPanel(new BorderLayout());

		cancel.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent mouseEvent) {
				KVimNew.this.dispose();
			}
		});

		ok.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent mouseEvent) {
				try {
					KVimNewFile.createFile(name.getText() + "." + fileType.getExtension(), path.getText());
					KVimNewFile.createNewTab(path.getText() + (path.getText().endsWith("/") ? "" : "/") +
							name.getText() + "." + fileType.getExtension());
					KVimNew.this.dispose();
				} catch(IOException e) {
					e.printStackTrace();
				}
			}
		});

		this.setLocationRelativeTo(null);
		this.setSize(300, 120);
		jPanel.add(cancel, BorderLayout.WEST);
		jPanel.add(ok, BorderLayout.EAST);
		namePanel.add(nameLabel, BorderLayout.WEST);
		namePanel.add(name, BorderLayout.CENTER);
		pathPanel.add(pathLabel, BorderLayout.WEST);
		pathPanel.add(path, BorderLayout.CENTER);
		this.getContentPane().add(namePanel, BorderLayout.NORTH);
		this.getContentPane().add(pathPanel, BorderLayout.CENTER);
		this.getContentPane().add(jPanel, BorderLayout.SOUTH);
	}
}
