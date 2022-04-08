package fr.kokhaviel.kvim.api.actions.file;

import fr.kokhaviel.kvim.KVim;
import fr.kokhaviel.kvim.api.actions.RecentFile;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class KVimOpen {

	public static void updateRecent(RecentFile newFile) {
		for(int i = 5; i > 1; i--) {
			KVim.kVimProperties.getRecentFiles().replace("name_" + i,
					KVim.kVimProperties.getRecentFiles().getProperty("name_" + (i - 1)));
			KVim.kVimProperties.getRecentFiles().replace("path_" + i,
					KVim.kVimProperties.getRecentFiles().getProperty("path_" + (i - 1)));

		}

		KVim.kVimProperties.getRecentFiles().replace("name_1", newFile.getName());
		KVim.kVimProperties.getRecentFiles().replace("path_1", newFile.getPath().toString());

		try {
			KVim.kVimProperties.getRecentFiles().store(new FileOutputStream(KVim.kVimProperties.getPropsDir() + "/recent.properties"), null);
		} catch(IOException e) {
			e.printStackTrace();
		}
	}

	public static String getFileContent(Path file) throws IOException {
		StringBuilder builder = new StringBuilder();
		Files.readAllLines(file).forEach(line -> builder.append(line).append("\n"));

		return builder.toString();
	}
}
