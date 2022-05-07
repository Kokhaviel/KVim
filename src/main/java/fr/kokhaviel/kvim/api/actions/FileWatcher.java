package fr.kokhaviel.kvim.api.actions;

import java.util.*;
import java.io.*;

public abstract class FileWatcher extends TimerTask {
	private long timeStamp;
	private final File file;

	public FileWatcher(File file) {
		this.file = file;
		this.timeStamp = file.lastModified();
	}

	public final void run() {
		long timeStamp = file.lastModified();

		if(this.timeStamp != timeStamp) {
			this.timeStamp = timeStamp;
			try {
				onChange(file);
			} catch(IOException e) {
				throw new RuntimeException(e);
			}
		}
	}

	protected abstract void onChange(File file) throws IOException;
}
