package fr.kokhaviel.kvim.api.actions.file;

import fr.kokhaviel.kvim.KVim;
import fr.kokhaviel.kvim.api.actions.RecentFile;
import fr.kokhaviel.kvim.api.props.KVimProperties;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class KVimOpenRecent {

	public static List<RecentFile> getRecents() {
		List<RecentFile> recentFiles = new ArrayList<>();
		final KVimProperties recentFiles1 = KVim.kVimProperties.getRecentFiles();

		for(int i = 1; i <= 5; i++) {
			recentFiles.add(new RecentFile(recentFiles1.getProperty("name_" + i), Paths.get(recentFiles1.getProperty("path_" + i))));
		}

		return recentFiles;
	}

	public static List<KVimRecentFile> getRecentFiles() {
		List<KVimRecentFile> recentFiles = new ArrayList<>();
		getRecents().forEach(recentFile -> recentFiles.add(new KVimRecentFile(recentFile.getName(), recentFile.getPath())));

		return recentFiles;
	}
}
