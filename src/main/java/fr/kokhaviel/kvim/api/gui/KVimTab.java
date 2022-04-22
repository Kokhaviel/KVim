package fr.kokhaviel.kvim.api.gui;

import fr.kokhaviel.kvim.api.actions.file.KVimOpen;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class KVimTab extends JTextArea {

	String filename;
	List<File> parents = new ArrayList<>();
	boolean untitled;
	boolean isProject;
	boolean hasAGitRepo;
	Path rootProjPath;
	Path rootGitPath;
	Path filePath;
	int index;
	String baseText;

	public KVimTab(Path file, int index) {
		this.index = index;
		if(file == null) {
			untitled = true;
			isProject = false;
			hasAGitRepo = false;
			this.filename = "Untitled";
		} else {
			this.filePath = file;
			this.filename = file.toFile().getName();

			if(Files.exists(file)) {
				try {
					baseText = KVimOpen.getFileContent(file);
				} catch(IOException e) {
					throw new RuntimeException(e);
				}
			}

			File parent = file.toFile();

			while(parent != null) {
				parents.add(parent.getParentFile());
				parent = parent.getParentFile();
			}

			parents.forEach(file1 -> {
				if(file1 == null) return;
				if(!isProject) {
					if(file1.listFiles() != null) {
						List<File> contents = Arrays.asList(file1.listFiles());

						contents.forEach(file2 -> {
							if(file2.getName().equals(".kvim")) {
								isProject = true;
								rootProjPath = file1.toPath();
							}
						});
					}
				}

				if(!hasAGitRepo) {
					if(file1.listFiles() != null) {
						List<File> contents = Arrays.asList(file1.listFiles());

						contents.forEach(file2 -> {
							if(file2.getName().equals(".git")) {
								hasAGitRepo = true;
								rootGitPath = file1.toPath();
							}
						});
					}
				}
			});
		}
	}


	public boolean isUntitled() {
		return untitled;
	}

	public boolean isProject() {
		return isProject;
	}

	public Path getRootProjPath() {
		return rootProjPath;
	}

	public Path getFilePath() {
		return filePath;
	}

	public boolean hasAGitRepo() {
		return hasAGitRepo;
	}

	public String getFilename() {
		return filename;
	}

	public int getIndex() {
		return index;
	}

	public String getBaseText() {
		return baseText;
	}

	public void setBaseText(String baseText) {
		this.baseText = baseText;
	}
}
