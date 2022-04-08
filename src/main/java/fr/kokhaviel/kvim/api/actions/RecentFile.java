package fr.kokhaviel.kvim.api.actions;

import java.nio.file.Path;

public class RecentFile {

	private final String name;
	private final Path path;

	public RecentFile(String name, Path path) {
		this.name = name;
		this.path = path;
	}

	public String getName() {
		return name;
	}

	public Path getPath() {
		return path;
	}

	@Override
	public String toString() {
		return name + " [" + path + "]";
	}
}
