package fr.kokhaviel.kvim.api;

import java.net.URL;

public enum FileType {

	UNTITLED   ("Untitled",   "",  ClassLoader.getSystemResource("lang/img/txt.png")),
	TEXT       ("Text",       "txt",  ClassLoader.getSystemResource("lang/img/txt.png")),
	JAVA       ("Java",       "java", ClassLoader.getSystemResource("lang/img/java.png")),
	KOTLIN     ("Kotlin",     "kt",   ClassLoader.getSystemResource("lang/img/kt.png")),
	PHP        ("PHP",        "php",  ClassLoader.getSystemResource("lang/img/php.png")),
	PYTHON     ("Python",     "py",   ClassLoader.getSystemResource("lang/img/py.png")),
	HTML       ("HTML",       "html", ClassLoader.getSystemResource("lang/img/html.png")),
	CSS        ("CSS",        "css",  ClassLoader.getSystemResource("lang/img/css.png")),
	JAVASCRIPT ("JavaScript", "js",   ClassLoader.getSystemResource("lang/img/js.png")),
	C          ("C",          "c",    ClassLoader.getSystemResource("lang/img/c.png")),
	CPP        ("C++",        "cpp",  ClassLoader.getSystemResource("lang/img/cpp.png")),
	CSHARP     ("C#",         "cs",   ClassLoader.getSystemResource("lang/img/cs.png")),
	H          ("C Header",   "h",    ClassLoader.getSystemResource("lang/img/h.png")),
	SQL        ("SQL",        "sql",  ClassLoader.getSystemResource("lang/img/sql.png")),
	SHELL      ("Shell",      "sh",   ClassLoader.getSystemResource("lang/img/sh.png")),
	GO         ("Go",         "go",   ClassLoader.getSystemResource("lang/img/go.png")),
	RUBY       ("Ruby",       "rb",   ClassLoader.getSystemResource("lang/img/rb.png"));


	final String name;
	final String extension;
	final URL langImgUri;

	FileType(String name, String extension, URL langImgUri) {
		this.name = name;
		this.extension = extension;
		this.langImgUri = langImgUri;
	}

	public String getName() {
		return name;
	}

	public String getExtension() {
		return extension;
	}

	public URL getLangImgUri() {
		return langImgUri;
	}
}
