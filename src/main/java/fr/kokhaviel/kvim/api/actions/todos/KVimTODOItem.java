package fr.kokhaviel.kvim.api.actions.todos;

import java.io.File;
import java.io.Serializable;

public class KVimTODOItem implements Serializable {

	private static final long serialVersionUID = 6568962346L;

	String name;
	File file;
	String relativePath;
	String contents;

	public KVimTODOItem(String name, File file, String relativePath, String contents) {
		this.name = name;
		this.file = file;
		this.relativePath = relativePath;
		this.contents = contents;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public File getFile() {
		return file;
	}

	public void setFile(File file) {
		this.file = file;
	}

	public String getRelativePath() {
		return relativePath;
	}

	public void setRelativePath(String relativePath) {
		this.relativePath = relativePath;
	}

	public String getContents() {
		return contents;
	}

	public void setContents(String contents) {
		this.contents = contents;
	}

	@Override
	public String toString() {
		return "KVimTODOItem{" +
				"name='" + name + '\'' +
				", file=" + file +
				", relativePath=" + relativePath +
				", contents='" + contents + '\'' +
				'}';
	}
}
