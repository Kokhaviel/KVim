package fr.kokhaviel.kvim.api.gui;

import javax.swing.*;
import java.awt.*;
import java.io.File;

import static fr.kokhaviel.kvim.KVim.kVimProperties;
import static fr.kokhaviel.kvim.api.props.DefaultKVimProperties.*;

public class KVimSaveChooser extends JFileChooser {

	String x = kVimProperties.getLastParams().getProperty("x");
	String y = kVimProperties.getLastParams().getProperty("y");
	public KVimSaveChooser() throws HeadlessException {
		this.setSize(420, 490);
		this.setDialogTitle("Save a file");
		this.setBackground(new Color(R, G, B));
		this.setCurrentDirectory(new File(kVimProperties.getLastParams().getProperty("last_save_file")));
		this.setLocation(Integer.parseInt(x) + 20, Integer.parseInt(y) + 20);
	}
}
