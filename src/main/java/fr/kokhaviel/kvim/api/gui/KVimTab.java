package fr.kokhaviel.kvim.api.gui;

import fr.kokhaviel.kvim.api.FileType;
import fr.kokhaviel.kvim.api.UndoTool;
import fr.kokhaviel.kvim.api.actions.file.KVimOpen;
import org.eclipse.jgit.api.Git;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class KVimTab extends JTextPane {

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
	Git gitRepository;
	FileType fileType;

	public KVimTab(Path file, int index) {
		this.index = index;
		if(file == null) {
			untitled = true;
			isProject = false;
			hasAGitRepo = false;
			this.filename = "Untitled";
			this.fileType = FileType.UNTITLED;
		} else {
			this.filePath = file;
			this.filename = file.toFile().getName();
			UndoTool.addUndoFunctionality(this);

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

			String[] ss = file.toFile().getName().split("\\.");
			String ext = ss[ss.length - 1];

			for(FileType type : FileType.values()) {
				if(type.getExtension().equals(ext)) this.fileType = type;
			}
			if(fileType == null) this.fileType = FileType.OTHER;

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

								try {
									gitRepository = Git.open(rootGitPath.toFile());
								} catch(IOException e) {
									throw new RuntimeException(e);
								}
							}
						});
					}
				}
			});

			this.addKeyListener(new KVimColorize());
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

	public void setIndex(int index) {
		this.index = index;
	}

	public void setHasAGitRepo(boolean hasAGitRepo) {
		this.hasAGitRepo = hasAGitRepo;
	}

	public void setRootGitPath(Path rootGitPath) {
		this.rootGitPath = rootGitPath;
	}

	public Git getGitRepository() {
		return gitRepository;
	}

	public void setGitRepository(Git gitRepository) {
		this.gitRepository = gitRepository;
	}

	public Path getRootGitPath() {
		return rootGitPath;
	}

	public FileType getFileType() {
		return fileType;
	}
}
