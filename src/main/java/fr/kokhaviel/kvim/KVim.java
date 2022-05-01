package fr.kokhaviel.kvim;

import fr.kokhaviel.kvim.api.props.KVimProperties;
import fr.kokhaviel.kvim.gui.KVimMain;
import fr.kokhaviel.kvim.gui.KVimWelcome;

import javax.swing.*;
import javax.swing.plaf.nimbus.NimbusLookAndFeel;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class KVim {

	public static KVimProperties kVimProperties = new KVimProperties(true);

	public static void main(String[] args) throws UnsupportedLookAndFeelException, InterruptedException, IOException {
		UIManager.setLookAndFeel(new NimbusLookAndFeel());
		new KVimWelcome().splash(1);
		KVimMain main;
		if (args.length >= 1) {
			List<Path> paths = new ArrayList<>();

			for(String arg : args) {
				paths.add(Paths.get(arg));
			}

			main = new KVimMain(paths);
		} else {
			main = new KVimMain();
		}

		main.setVisible(true);
	}
}
