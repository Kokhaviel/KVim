package fr.kokhaviel.kvim.api.actions.file;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public class KVimSave {

	public static void saveFile(Path file, String text) throws IOException {
		new FileOutputStream(String.valueOf(file)).write(text.getBytes(StandardCharsets.UTF_8));
	}

}
