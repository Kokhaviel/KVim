package fr.kokhaviel.kvim.api.props;

import javax.swing.*;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public class KVimProperties extends Properties {

	private final Path propsDir;
	private KVimProperties recentFilesProperties;
	private KVimProperties lastOpenProperties;

	public KVimProperties(boolean init) {
		propsDir = Paths.get(System.getProperty("user.home") + "/.kvim");

		if(init) {
			DefaultKVimProperties.init();
			recentFilesProperties = new KVimProperties(false);
			lastOpenProperties = new KVimProperties(false);
			createDir();
			loadProperties();
		}
	}

	public void createDir() {
		boolean mkdirExit;

		if(!Files.exists(propsDir)) {
			mkdirExit = propsDir.toFile().mkdirs();

			if(mkdirExit) {
				System.out.println("Successfully created " + propsDir);
			} else {
				JOptionPane.showMessageDialog(null,
						"Unable to create ... ! Check Permissions ...", "Unable to create properties directory!"
						, JOptionPane.ERROR_MESSAGE);
			}
		}
	}

	public void loadProperties() {
		try {
			recentFilesProperties.load(new FileInputStream(propsDir + "/recent.properties"));
		} catch(IOException e) {
			recentFilesProperties = DefaultKVimProperties.DEFAULT_RECENT_FILES_PROPERTIES;
			try {
				recentFilesProperties.store(new FileOutputStream(propsDir + "/recent.properties"), null);
			} catch(IOException ex) {
				ex.printStackTrace();
			}
		}

		try {
			lastOpenProperties.load(new FileInputStream(propsDir + "/last_params.properties"));
		} catch(IOException e) {
			lastOpenProperties = DefaultKVimProperties.DEFAULT_LAST_OPEN_PROPERTIES;

			try {
				lastOpenProperties.store(new FileOutputStream(propsDir + "/last_params.properties"), null);
			} catch(IOException ex) {
				ex.printStackTrace();
			}
		}
	}

	public void update() {
		loadProperties();
	}

	public Path getPropsDir() {
		return propsDir;
	}

	public KVimProperties getRecentFiles() {
		return recentFilesProperties;
	}

	public KVimProperties getLastParams() {
		return lastOpenProperties;
	}
}
