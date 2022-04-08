package fr.kokhaviel.kvim.api.actions.file;

import javax.swing.*;
import java.nio.file.Path;

public class KVimRecentFile extends JMenuItem {

	Path path;

	public KVimRecentFile(String title, Path path) {
		super(title);
		this.path = path;
	}
}
