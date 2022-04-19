package fr.kokhaviel.kvim.api.gui;

import fr.kokhaviel.kvim.KVim;

import javax.swing.*;
import java.awt.*;
import java.io.File;

import static fr.kokhaviel.kvim.KVim.kVimProperties;
import static fr.kokhaviel.kvim.api.props.DefaultKVimProperties.*;

public class KVimFileChooser extends JFileChooser {

	String x = kVimProperties.getLastParams().getProperty("x");
	String y = kVimProperties.getLastParams().getProperty("y");
	public KVimFileChooser() throws HeadlessException {
		this.setSize(420, 490);
		this.setDialogTitle("Open a file");
		this.setCurrentDirectory(new File(kVimProperties.getLastParams().getProperty("last_open_file")));
		this.setBackground(new Color(R, G, B));
		this.setLocation(Integer.parseInt(x) + 20, Integer.parseInt(y) + 20);
	}
}
