package fr.kokhaviel.kvim.api;

import java.net.URL;
import java.util.Collections;
import java.util.List;

public enum FileType {

	UNTITLED   ("Untitled",   "",     ClassLoader.getSystemResource("lang/img/txt.png"), Collections.emptyList()),
	TEXT       ("Text",       "txt",  ClassLoader.getSystemResource("lang/img/txt.png"), Collections.emptyList()),
	JAVA       ("Java",       "java", ClassLoader.getSystemResource("lang/img/java.png"), ColorSchemes.JAVA_KEYWORD),
	KOTLIN     ("Kotlin",     "kt",   ClassLoader.getSystemResource("lang/img/kt.png"), ColorSchemes.KOTLIN_KEYWORDS),
	PHP        ("PHP",        "php",  ClassLoader.getSystemResource("lang/img/php.png"), ColorSchemes.PHP_KEYWORDS),
	PYTHON     ("Python",     "py",   ClassLoader.getSystemResource("lang/img/py.png"), ColorSchemes.PYTHON_KEYWORDS),
	HTML       ("HTML",       "html", ClassLoader.getSystemResource("lang/img/html.png"), ColorSchemes.HTML_KEYWORDS),
	CSS        ("CSS",        "css",  ClassLoader.getSystemResource("lang/img/css.png"), ColorSchemes.CSS_KEYWORDS),
	JAVASCRIPT ("JavaScript", "js",   ClassLoader.getSystemResource("lang/img/js.png"), ColorSchemes.JS_KEYWORDS),
	C          ("C",          "c",    ClassLoader.getSystemResource("lang/img/c.png"), ColorSchemes.C_KEYWORDS),
	CPP        ("C++",        "cpp",  ClassLoader.getSystemResource("lang/img/cpp.png"), ColorSchemes.CPP_KEYWORDS),
	CSHARP     ("C#",         "cs",   ClassLoader.getSystemResource("lang/img/cs.png"), ColorSchemes.C_SHARP_KEYWORD),
	H          ("C Header",   "h",    ClassLoader.getSystemResource("lang/img/h.png"), ColorSchemes.C_KEYWORDS),
	SQL        ("SQL",        "sql",  ClassLoader.getSystemResource("lang/img/sql.png"), ColorSchemes.SQL_KEYWORDS),
	SHELL      ("Shell",      "sh",   ClassLoader.getSystemResource("lang/img/sh.png"), ColorSchemes.BASH_KEYWORDS),
	GO         ("Go",         "go",   ClassLoader.getSystemResource("lang/img/go.png"), ColorSchemes.GO_KEYWORDS),
	RUBY       ("Ruby",       "rb",   ClassLoader.getSystemResource("lang/img/rb.png"), ColorSchemes.RUBY_KEYWORDS),

	ADA        ("Ada",        "ada", null, ColorSchemes.ADA_KEYWORDS),
	ASM        ("Assembly",   "asm", null, ColorSchemes.ASSEMBLY_KEYWORDS),
	ASM2       ("Assembly",   "S", null, ColorSchemes.ASSEMBLY_KEYWORDS),
	LUA        ("Lua",        "lua", null, ColorSchemes.LUA_KEYWORDS),
	RUST       ("Rust",       "rs", null, ColorSchemes.RUST_KEYWORDS),
	SCALA      ("Scala",      "scala", null, ColorSchemes.SCALA_KEYWORD),
	SWIFT      ("Swift",      "swift", null, ColorSchemes.SWIFT_KEYWORDS),
	OTHER      ("Other",      "", null, Collections.emptyList());



	final String name;
	final String extension;
	final URL langImgUri;
	final List<String> keywords;

	FileType(String name, String extension, URL langImgUri, List<String> keywords) {
		this.name = name;
		this.extension = extension;
		this.langImgUri = langImgUri;
		this.keywords = keywords;
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

	public List<String> getKeywords() {
		return keywords;
	}
}
